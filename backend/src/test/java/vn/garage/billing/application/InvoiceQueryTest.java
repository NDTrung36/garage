package vn.garage.billing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.garage.billing.domain.InvoiceStore;
import vn.garage.identity.application.StaffDirectory;
import vn.garage.repair.application.RepairInvoiceLines;
import vn.garage.repair.domain.RepairTotals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceQueryTest {
    private static final OffsetDateTime ISSUED_AT = OffsetDateTime.parse("2026-11-19T08:00:00+07:00");
    private final InvoiceStore store = mock(InvoiceStore.class);
    private final RepairInvoiceLines repairLines = mock(RepairInvoiceLines.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final InvoiceQuery query = new InvoiceQuery(store, repairLines, staff);

    @Test void listValidatesFiltersAndReturnsPageMetadata() {
        var paid = InvoiceStore.PaymentStatus.PAID;
        when(store.list(paid, 2, 20)).thenReturn(List.of(entry(51, "PAID")));
        when(store.count(paid)).thenReturn(41L);

        var page = query.list("PAID", 2, 20);
        assertEquals(3, page.totalPages());
        assertEquals(41, page.totalElements());
        assertEquals(2, page.page());
        assertEquals(20, page.size());
        assertEquals("19", page.items().getFirst().repairOrderId());
        assertThrows(IllegalArgumentException.class, () -> query.list("PENDING", 0, 20));
        assertThrows(IllegalArgumentException.class, () -> query.list(null, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> query.list(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> query.list(null, 0, 101));
        verifyNoInteractions(repairLines, staff);
    }

    @Test void detailReadsStoredTotalAndOnlyExposesInvoiceFields() {
        when(store.findById(7)).thenReturn(Optional.of(new InvoiceStore.Details(
                entry(52, "PAID"), 1, 52L, "CASH", 41L, ISSUED_AT)));
        when(staff.displayNames(List.of(41L))).thenReturn(Map.of(41L, "Nhân viên"));
        var line = new RepairInvoiceLines.Line("11", RepairTotals.Type.LABOR, "Công sửa", "giờ", "0.500", 101, 51);
        when(repairLines.forCompletedOrder(19)).thenReturn(List.of(line));

        var detail = query.detail(7);
        assertEquals(52, detail.totalAmount());
        assertEquals("41", detail.paidBy());
        assertEquals("Nhân viên", detail.paidByDisplayName());
        assertEquals(List.of(line), detail.items());
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(detail);
        assertEquals(13, json.size());
        assertEquals(7, json.get("items").get(0).size());
        assertTrue(json.path("id").isTextual());
        assertFalse(json.has("internalNotes"));
        assertFalse(json.has("ledger"));
        assertFalse(json.has("audit"));
    }

    @Test void zeroInvoiceHasNoLinesOrPaymentDetails() {
        when(store.findById(7)).thenReturn(Optional.of(new InvoiceStore.Details(
                entry(0, "UNPAID"), 0, null, null, null, null)));
        when(repairLines.forCompletedOrder(19)).thenReturn(List.of());

        var detail = query.detail(7);
        assertEquals(0, detail.totalAmount());
        assertTrue(detail.items().isEmpty());
        assertNull(detail.paidAmount());
        assertNull(detail.paymentMethod());
        assertNull(detail.paidBy());
        assertNull(detail.paidByDisplayName());
        assertNull(detail.paidAt());
        verifyNoInteractions(staff);
    }

    @Test void rejectsInvalidAndMissingIdsBeforeReadingRepairData() {
        assertThrows(IllegalArgumentException.class, () -> query.detail(0));
        assertThrows(InvoiceQuery.InvoiceNotFound.class, () -> query.detail(99));
        verifyNoInteractions(repairLines, staff);
    }

    private static InvoiceStore.ListEntry entry(long total, String status) {
        return new InvoiceStore.ListEntry(new InvoiceStore.Invoice(7, "INV-123456789ABC", total, status, ISSUED_AT), 19);
    }
}
