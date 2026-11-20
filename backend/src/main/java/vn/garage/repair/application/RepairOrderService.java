package vn.garage.repair.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import vn.garage.catalog.application.BookingServiceCatalog;
import vn.garage.repair.domain.RepairLaborRepository;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.repair.domain.RepairTotals;
import vn.garage.support.transaction.SqlServerWriteTransactions;

@Service
public class RepairOrderService {
    private static final Pattern QUANTITY = Pattern.compile("(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,3})?");
    private static final BigDecimal MAX_SAFE = RepairTotals.MAX_SAFE_AMOUNT;
    private static final String LABOR_UNIT = "Lần";

    private final RepairOrderStore orders;
    private final RepairLaborRepository labor;
    private final RepairPartRepository items;
    private final BookingServiceCatalog services;
    private final SqlServerWriteTransactions writes;
    private final Clock clock;

    public RepairOrderService(RepairOrderStore orders, RepairLaborRepository labor, RepairPartRepository items,
                              BookingServiceCatalog services, SqlServerWriteTransactions writes, Clock clock) {
        this.orders = orders; this.labor = labor; this.items = items; this.services = services;
        this.writes = writes; this.clock = clock;
    }

    public long patchHeader(long orderId, long expectedVersion, HeaderPatch patch, long actorId, String role) {
        requireStaff(actorId, role);
        requireOrderId(orderId);
        if (patch == null || expectedVersion < 0 || !patch.hasChanges()) throw new InvalidLaborInput();
        validateHeader(patch);
        return writes.execute(() -> {
            RepairOrderStore.RepairOrderContext order = lockInProgress(orderId, expectedVersion);
            RepairOrderStore.RepairOrderData current = orders.findDetails(orderId).orElseThrow(RepairOrderNotFound::new);
            boolean changed = patch.odometerPresent() && !java.util.Objects.equals(current.odometerKm(), patch.odometerKm())
                    || patch.conditionNotesPresent() && !java.util.Objects.equals(current.conditionNotes(), clean(patch.conditionNotes()))
                    || patch.internalNotesPresent() && !java.util.Objects.equals(current.internalNotes(), clean(patch.internalNotes()));
            if (!changed) return order.version();
            if (!orders.updateHeader(orderId, expectedVersion, patch.odometerPresent(), patch.odometerKm(),
                    patch.conditionNotesPresent(), clean(patch.conditionNotes()),
                    patch.internalNotesPresent(), clean(patch.internalNotes()))) throw new StaleVersion();
            return order.version() + 1;
        });
    }

    public LaborResult addLabor(long orderId, Long serviceId, String description, String rawQuantity,
                                Long unitPrice, long expectedVersion, long actorId, String role) {
        requireStaff(actorId, role);
        requireOrderId(orderId);
        requireVersion(expectedVersion);
        String normalizedDescription = description(description);
        BigDecimal quantity = quantity(rawQuantity);
        long normalizedPrice = price(unitPrice);
        if (serviceId != null && serviceId < 1) throw new InvalidLaborInput();
        return writes.execute(() -> {
            RepairOrderStore.RepairOrderContext order = lockInProgress(orderId, expectedVersion);
            if (serviceId != null) services.activeForBooking(List.of(serviceId), order.vehicleType());
            RepairLaborRepository.LaborItem inserted = labor.insertLabor(orderId, serviceId,
                    normalizedDescription, LABOR_UNIT, quantity, normalizedPrice);
            RepairTotals.Totals totals = totals(orderId);
            long version = items.incrementOrderVersion(orderId);
            return new LaborResult(Long.toString(inserted.id()), Long.toString(orderId), version, totals);
        });
    }

    public LaborResult updateLabor(long orderId, long itemId, LaborPatch patch,
                                  long actorId, String role) {
        requireStaff(actorId, role);
        requireOrderId(orderId);
        requireItemId(itemId);
        if (patch == null || patch.expectedVersion() < 0 || !patch.hasChanges()) throw new InvalidLaborInput();
        String description = patch.descriptionPresent() ? description(patch.description()) : null;
        BigDecimal quantity = patch.quantityPresent() ? quantity(patch.quantity()) : null;
        Long unitPrice = patch.unitPricePresent() ? price(patch.unitPrice()) : null;
        return writes.execute(() -> {
            RepairOrderStore.RepairOrderContext order = lockInProgress(orderId, patch.expectedVersion());
            RepairLaborRepository.LaborItem current = labor.findLaborForUpdate(orderId, itemId)
                    .orElseThrow(LaborRepositoryNotFound::new);
            if (current.voided()) throw new LaborItemVoided();
            String nextDescription = patch.descriptionPresent() ? description : current.description();
            BigDecimal nextQuantity = patch.quantityPresent() ? quantity : current.quantity();
            long nextPrice = patch.unitPricePresent() ? unitPrice : current.unitPrice().longValueExact();
            if (nextDescription.equals(current.description()) && nextQuantity.compareTo(current.quantity()) == 0
                    && nextPrice == current.unitPrice().longValueExact()) {
                return new LaborResult(Long.toString(itemId), Long.toString(orderId), order.version(), totals(orderId));
            }
            labor.updateLabor(orderId, itemId, nextDescription, nextQuantity, nextPrice);
            RepairTotals.Totals totals = totals(orderId);
            long version = items.incrementOrderVersion(orderId);
            return new LaborResult(Long.toString(itemId), Long.toString(orderId), version, totals);
        });
    }

    public void voidLabor(long orderId, long itemId, long expectedVersion, long actorId, String role) {
        requireStaff(actorId, role);
        requireOrderId(orderId);
        requireItemId(itemId);
        requireVersion(expectedVersion);
        writes.execute(() -> {
            RepairOrderStore.RepairOrderContext order = lockInProgress(orderId, expectedVersion);
            RepairLaborRepository.LaborItem current = labor.findLaborForUpdate(orderId, itemId)
                    .orElseThrow(LaborRepositoryNotFound::new);
            if (current.voided()) throw new LaborItemVoided();
            OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            if (!labor.voidLabor(orderId, itemId, actorId, now)) throw new LaborRepositoryNotFound();
            totals(orderId);
            items.incrementOrderVersion(orderId);
            return null;
        });
    }

    private RepairOrderStore.RepairOrderContext lockInProgress(long orderId, long expectedVersion) {
        RepairOrderStore.RepairOrderContext hint = orders.findById(orderId).orElseThrow(RepairOrderNotFound::new);
        RepairOrderStore.RepairOrderContext locked = orders.findByIdForUpdate(orderId)
                .orElseThrow(RepairOrderNotFound::new);
        if (locked.status() != RepairStatus.IN_PROGRESS) throw new RepairNotInProgress();
        if (locked.version() != expectedVersion) throw new StaleVersion();
        return new RepairOrderStore.RepairOrderContext(locked.id(), locked.status(), locked.version(), hint.vehicleType());
    }

    private RepairTotals.Totals totals(long orderId) {
        return RepairTotals.calculate(items.totalLines(orderId).stream()
                .map(line -> new RepairTotals.Line(line.type(), line.quantity(), line.unitPrice(), line.voided())).toList());
    }

    private static void validateHeader(HeaderPatch patch) {
        if (patch.odometerPresent() && patch.odometerKm() != null && patch.odometerKm() < 0) throw new InvalidLaborInput();
        if (patch.conditionNotesPresent() && tooLong(patch.conditionNotes())
                || patch.internalNotesPresent() && tooLong(patch.internalNotes())) throw new InvalidLaborInput();
    }

    private static String description(String input) {
        String value = input == null ? "" : input.strip();
        if (value.isEmpty() || value.length() > 200) throw new InvalidLaborInput();
        return value;
    }

    private static BigDecimal quantity(String input) {
        if (input == null || !QUANTITY.matcher(input).matches()) throw new InvalidLaborInput();
        BigDecimal value = new BigDecimal(input);
        if (value.signum() <= 0) throw new InvalidLaborInput();
        return value.setScale(3);
    }

    private static long price(Long input) {
        if (input == null || input < 0 || BigDecimal.valueOf(input).compareTo(MAX_SAFE) > 0) throw new InvalidLaborInput();
        return input;
    }

    private static boolean tooLong(String text) { return text != null && text.length() > 2000; }
    private static String clean(String text) { if (text == null) return null; String value = text.strip(); return value.isEmpty() ? null : value; }

    private static void requireStaff(long actorId, String role) {
        if (!"STAFF".equals(role) && !"ADMIN".equals(role)) throw new IllegalStateException("FORBIDDEN");
        if (actorId < 1) throw new IllegalArgumentException("INVALID_ACTOR_ID");
    }
    private static void requireOrderId(long id) { if (id < 1) throw new IllegalArgumentException("INVALID_REPAIR_ORDER_ID"); }
    private static void requireItemId(long id) { if (id < 1) throw new IllegalArgumentException("INVALID_LABOR_ITEM_ID"); }
    private static void requireVersion(long version) { if (version < 0) throw new InvalidLaborInput(); }

    public record HeaderPatch(boolean odometerPresent, Integer odometerKm,
                              boolean conditionNotesPresent, String conditionNotes,
                              boolean internalNotesPresent, String internalNotes) {
        public boolean hasChanges() { return odometerPresent || conditionNotesPresent || internalNotesPresent; }
    }

    public record LaborPatch(long expectedVersion, boolean descriptionPresent, String description,
                             boolean quantityPresent, String quantity,
                             boolean unitPricePresent, Long unitPrice) {
        public boolean hasChanges() { return descriptionPresent || quantityPresent || unitPricePresent; }
    }

    public record LaborResult(String itemId, String repairOrderId, long version, RepairTotals.Totals totals) { }

    public static final class InvalidLaborInput extends RuntimeException { }
    public static final class RepairOrderNotFound extends RuntimeException { }
    public static final class RepairNotInProgress extends RuntimeException { }
    public static final class StaleVersion extends RuntimeException { }
    public static final class LaborRepositoryNotFound extends RuntimeException { }
    public static final class LaborItemVoided extends RuntimeException { }
}
