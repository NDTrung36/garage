package vn.garage.repair.infrastructure;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.garage.repair.application.RepairOrderService;
import vn.garage.repair.application.RepairPartCommands;
import vn.garage.repair.domain.BookingStore.BookingStatus;
import vn.garage.repair.domain.RepairStatus;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RepairConcurrencyIT {
    @BeforeAll static void start() { RepairCompletionIT.start(); }
    @AfterAll static void stop() { RepairCompletionIT.stop(); }

    @Test void twoCompletionsReturnTheSameInvoice() throws Exception {
        var orders = gatedOrders();
        try (var run = new RepairCompletionIT.Run(orders)) {
            var results = race(orders, () -> run.complete(0), () -> run.complete(0));
            assertEquals(results.first(), results.second());
            assertEquals(1, run.invoiceCount());
            assertEquals(1, results.first().version());
            assertEquals(1, run.count("""
                    SELECT COUNT(*) FROM dbo.repair_orders r JOIN dbo.bookings b ON b.id=r.booking_id
                    JOIN dbo.invoices i ON i.repair_order_id=r.id
                    WHERE r.id=? AND r.status='COMPLETED' AND b.status='COMPLETED'
                      AND r.version=1 AND i.payment_status='UNPAID' AND i.total_amount=0
                    """, run.orderId));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void checkInReplayKeepsTheSlotAndNeverReopensTheOrder(boolean checkInFirst) throws Exception {
        var orders = gatedOrders();
        try (var run = new RepairCompletionIT.Run(orders)) {
            if (checkInFirst) {
                var results = race(orders, run::checkIn, () -> run.complete(0));
                assertEquals(RepairStatus.IN_PROGRESS, results.first().repairStatus());
                assertEquals(RepairStatus.COMPLETED, results.second().status());
            } else {
                var results = race(orders, () -> run.complete(0), run::checkIn);
                assertEquals(RepairStatus.COMPLETED, results.second().repairStatus());
                assertEquals(BookingStatus.COMPLETED, results.second().bookingStatus());
            }
            assertEquals(Long.toString(run.orderId), run.checkIn().repairOrderId());
            assertEquals(BookingStatus.COMPLETED, run.checkIn().bookingStatus());
            assertEquals(1, run.count("""
                    SELECT COUNT(*) FROM bookings WHERE slot_id=?
                    AND status IN ('CONFIRMED','CHECKED_IN','COMPLETED')
                    """, run.fixture.slotId()));
            assertEquals(1, run.invoiceCount());
        }
    }

    @ParameterizedTest @EnumSource(Edit.class)
    void editCommittedFirstIsIncludedAfterRefreshingVersion(Edit edit) throws Exception {
        var orders = gatedOrders();
        try (var run = new RepairCompletionIT.Run(orders)) {
            Runnable write = edit.prepare(run);
            long version = edit == Edit.RETURN ? 1 : 0;
            race(orders, () -> { write.run(); return null; }, () ->
                    assertThrows(RepairOrderService.StaleVersion.class, () -> run.complete(version)));
            assertEquals(edit == Edit.RETURN ? 0 : 51, run.complete(version + 1).invoice().totalAmount());
            assertEquals(1, run.invoiceCount());
        }
    }

    @ParameterizedTest @EnumSource(Edit.class)
    void completionCommittedFirstRejectsTheEdit(Edit edit) throws Exception {
        var orders = gatedOrders();
        try (var run = new RepairCompletionIT.Run(orders)) {
            Runnable write = edit.prepare(run);
            long version = edit == Edit.RETURN ? 1 : 0;
            var results = race(orders, () -> run.complete(version), () -> {
                if (edit == Edit.LABOR) return assertThrows(RepairOrderService.RepairNotInProgress.class, write::run);
                return assertThrows(RepairPartCommands.RepairNotInProgress.class, write::run);
            });
            assertEquals(edit == Edit.RETURN ? 51 : 0, results.first().invoice().totalAmount());
            assertEquals(edit == Edit.RETURN ? 1 : 0, run.count(
                    "SELECT COUNT(*) FROM stock_movements WHERE part_id=?", run.partId));
            assertEquals(0, run.count("SELECT COUNT(*) FROM repair_items WHERE repair_order_id=? AND item_type='LABOR'", run.orderId));
            assertEquals(results.first(), run.complete(version));
        }
    }

    enum Edit {
        ISSUE, RETURN, LABOR;

        Runnable prepare(RepairCompletionIT.Run run) {
            return switch (this) {
                case LABOR -> run::addLabor;
                case ISSUE -> () -> run.parts.issue(run.orderId, run.partId, "0.500", "issue",
                        run.fixture.userId(), "STAFF");
                case RETURN -> {
                    var result = run.parts.issue(run.orderId, run.partId, "0.500", "issue", run.fixture.userId(), "STAFF");
                    long issueId = Long.parseLong(result.body().movement().id());
                    yield () -> run.parts.returnPart(run.orderId, issueId, "0.500", "return", run.fixture.userId(), "STAFF");
                }
            };
        }
    }

    private static GatedOrders gatedOrders() {
        return new GatedOrders(new JdbcTemplate(RepairCompletionIT.source));
    }

    private static <A, B> Results<A, B> race(GatedOrders orders, Callable<A> first, Callable<B> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                orders.gate.set(true);
                return first.call();
            });
            var b = pool.submit(() -> {
                assertTrue(orders.locked.await(5, TimeUnit.SECONDS));
                orders.overlap.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            return new Results<>(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private record Results<A, B>(A first, B second) { }

    private static final class GatedOrders extends JdbcRepairOrderStore {
        private final ThreadLocal<Boolean> gate = ThreadLocal.withInitial(() -> false);
        private final CountDownLatch locked = new CountDownLatch(1);
        private final CyclicBarrier overlap = new CyclicBarrier(2);

        GatedOrders(JdbcTemplate db) { super(db); }

        @Override public Optional<RepairOrderContext> findByIdForUpdate(long id) {
            var order = super.findByIdForUpdate(id);
            afterLock();
            return order;
        }

        @Override public Optional<RepairOrder> findByBookingForUpdate(long id) {
            var order = super.findByBookingForUpdate(id);
            afterLock();
            return order;
        }

        private void afterLock() {
            if (!gate.get()) return;
            gate.remove();
            locked.countDown();
            try {
                overlap.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
