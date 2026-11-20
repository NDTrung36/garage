package vn.garage.inventory.api;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.math.BigDecimal;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.garage.identity.api.IdentityExceptionHandler;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.inventory.application.PartCatalog;
import vn.garage.inventory.domain.PartPage;
import vn.garage.inventory.domain.PartRepository;
import vn.garage.inventory.domain.PartView;
import vn.garage.inventory.application.StockCommands;
import vn.garage.inventory.application.StockHistory;
import vn.garage.inventory.domain.StockMovement;
import vn.garage.inventory.domain.StockMovementRepository;
import vn.garage.inventory.domain.StockQuantity;
import vn.garage.repair.application.StockHistoryQuery;
import vn.garage.repair.api.StaffStockMovementsController;
import vn.garage.support.audit.AuditWriter;
import vn.garage.support.idempotency.IdempotencyExecutor;
import vn.garage.support.idempotency.JdbcIdempotencyStore;
import vn.garage.support.transaction.SqlServerWriteTransactions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InventoryApiTest {
    private PartRepository repository;
    private PartCatalog catalog;
    private AuditWriter audit;
    private StockMovementRepository movements;
    private JdbcIdempotencyStore idempotencyStore;
    private SqlServerWriteTransactions writes;
    private StockHistoryQuery staffHistory;
    private MockMvc mvc;
    private Authentication admin;
    private Authentication staff;

    @BeforeEach
    void setUp() {
        repository = mock(PartRepository.class);
        audit = mock(AuditWriter.class);
        catalog = new PartCatalog(repository, audit);
        movements = mock(StockMovementRepository.class);
        idempotencyStore = mock(JdbcIdempotencyStore.class);
        writes = mock(SqlServerWriteTransactions.class);
        when(writes.execute(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var idempotency = new IdempotencyExecutor(idempotencyStore, writes,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC));
        var commands = new StockCommands(movements, idempotency);
        var history = mock(StockHistoryQuery.class);
        staffHistory = history;
        mvc = MockMvcBuilders.standaloneSetup(new PartController(catalog), new AdminStockController(commands),
                        new StaffStockMovementsController(history))
                .setControllerAdvice(new InventoryErrors(), new IdentityExceptionHandler()).build();
        var adminPrincipal = new GaragePrincipal(7, "admin", "Quản trị", "ADMIN", 0, "hash");
        var staffPrincipal = new GaragePrincipal(8, "staff", "Nhân viên", "STAFF", 0, "hash");
        admin = new UsernamePasswordAuthenticationToken(adminPrincipal, "", adminPrincipal.getAuthorities());
        staff = new UsernamePasswordAuthenticationToken(staffPrincipal, "", staffPrincipal.getAuthorities());
    }

    @Test
    void returnsPagedParts() throws Exception {
        when(repository.list(0, 20, null, null, null)).thenReturn(new PartPage(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/staff/parts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void rejectsPaginationAndVehicleTypeOutsideSupportedRange() throws Exception {
        mvc.perform(get("/api/staff/parts").principal(staff).queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mvc.perform(get("/api/staff/parts").principal(staff).queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mvc.perform(get("/api/staff/parts").principal(staff).queryParam("vehicleType", "TRUCK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VEHICLE_TYPE"));
        verifyNoInteractions(repository);
    }

    @Test
    void createsPartAndMapsDuplicateWithoutIdentityFallback() throws Exception {
        when(repository.create(any())).thenReturn(view("1", "OIL"));
        mvc.perform(post("/api/admin/parts").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":" oil ","name":"Dầu nhớt","unit":"Lít","vehicleType":"BOTH","referencePrice":0}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OIL"))
                .andExpect(jsonPath("$.stockQuantity").value("0.000"))
                .andExpect(jsonPath("$.name").value("Dầu nhớt"))
                .andExpect(jsonPath("$.referencePrice").value(0))
                .andExpect(jsonPath("$.active").value(true));
        verify(audit).write(eq(7L), eq("PART_CREATED"), eq("part"), eq(1L), isNull(), isNull(), any());

        when(repository.create(any())).thenReturn(view("2", "FILTER"));
        mvc.perform(post("/api/admin/parts").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"FILTER","name":"Lọc gió","unit":"Cái","vehicleType":"CAR","referencePrice":125000}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("FILTER"));

        when(repository.create(any())).thenThrow(new PartCatalog.DuplicateCode());
        mvc.perform(post("/api/admin/parts").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OIL","name":"Dầu nhớt","unit":"Lít","vehicleType":"BOTH","referencePrice":0}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PART_CODE_EXISTS"));
    }

    @Test
    void inventoryConstraintFailureDoesNotFallThroughToIdentityAdvice() throws Exception {
        when(repository.create(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "constraint UX_parts_code"));

        mvc.perform(post("/api/admin/parts").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OIL","name":"Dầu","unit":"Lít","vehicleType":"CAR","referencePrice":0}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_CONFLICT"));
    }

    @Test
    void mapsMissingAndLockedUnitErrors() throws Exception {
        when(repository.update(eq(20L), any(), any(), any(), any(), any(), eq(0L))).thenReturn(java.util.Optional.empty());
        mvc.perform(patch("/api/admin/parts/20").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Dầu","expectedVersion":0}
                """))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PART_NOT_FOUND"));

        when(repository.findById(21L)).thenReturn(java.util.Optional.of(view("21", "OIL")));
        when(repository.update(eq(21L), any(), any(), any(), any(), any(), eq(0L)))
                .thenThrow(new PartCatalog.UnitLocked());
        mvc.perform(patch("/api/admin/parts/21").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"unit":"Bình","expectedVersion":0}
                """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_UNIT_LOCKED"));
    }

    @Test
    void rejectsFractionalAndOutOfRangePricesBeforeCallingCatalog() throws Exception {
        mvc.perform(post("/api/admin/parts").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OIL","name":"Dầu","unit":"Lít","vehicleType":"CAR","referencePrice":10.5}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE_PRICE"));
        mvc.perform(post("/api/admin/parts").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OIL","name":"Dầu","unit":"Lít","vehicleType":"CAR","referencePrice":9007199254740992}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
        verify(repository, never()).create(any());
        verify(repository, never()).update(anyLong(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void rejectsUnknownWriteFields() throws Exception {
        mvc.perform(post("/api/admin/parts").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OIL","name":"Dầu","unit":"Lít","vehicleType":"CAR","referencePrice":0,"stockQuantity":"99.000"}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
        verifyNoInteractions(audit);
    }

    @Test
    void staffCanReadButCannotWriteCatalog() throws Exception {
        when(repository.list(0, 20, null, null, null)).thenReturn(new PartPage(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/staff/parts").principal(staff))
                .andExpect(status().isOk());

        mvc.perform(post("/api/admin/parts").principal(staff).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OIL","name":"Dầu","unit":"Lít","vehicleType":"CAR","referencePrice":0}
                """))
                .andExpect(status().isForbidden());
        verify(repository).list(0, 20, null, null, null);
        verify(repository, never()).create(any());
        verify(repository, never()).update(anyLong(), any(), any(), any(), any(), any(), anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void adminReceiptTrimsVietnameseReasonAndNoteAndReturnsExactBalances() throws Exception {
        when(movements.lockPart(11L)).thenReturn(java.util.Optional.of(new StockMovementRepository.LockedPart(
                11, "OIL", StockQuantity.fromDatabase(BigDecimal.ZERO))));
        when(movements.insert(any())).thenAnswer(call -> {
            var row = (StockMovementRepository.NewMovement) call.getArgument(0);
            return movement(51, row);
        });

        mvc.perform(post("/api/admin/stock-receipts").principal(admin).header("Idempotency-Key", "receipt-01")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"partId":"11","quantity":"2.500","reason":"  Nhập hàng  ","note":"  Lô A  "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.stockQuantity").value("2.500"))
                .andExpect(jsonPath("$.movement.partId").value("11"))
                .andExpect(jsonPath("$.movement.quantityBefore").value("0.000"))
                .andExpect(jsonPath("$.movement.quantityAfter").value("2.500"))
                .andExpect(jsonPath("$.movement.reason").value("Nhập hàng"))
                .andExpect(jsonPath("$.movement.note").value("Lô A"));

        var inserted = org.mockito.ArgumentCaptor.forClass(StockMovementRepository.NewMovement.class);
        verify(movements).insert(inserted.capture());
        assertEquals(StockMovement.Type.RECEIPT, inserted.getValue().type());
        assertEquals("Nhập hàng", inserted.getValue().reason());
        assertEquals("Lô A", inserted.getValue().note());
        assertEquals(7L, inserted.getValue().actorId());
        verify(movements).updateStock(11L, StockQuantity.parsePositive("2.500"));
        verify(idempotencyStore).save(eq("inventory.stock.receipt:actor:7"), eq("receipt-01"), anyString(),
                eq(201), anyString(), any(), any());
    }

    @Test
    void adjustmentBelowZeroIsConflictAndDoesNotWriteBalanceOrLedger() throws Exception {
        when(movements.lockPart(11L)).thenReturn(java.util.Optional.of(new StockMovementRepository.LockedPart(
                11, "OIL", StockQuantity.parsePositive("2.000"))));

        mvc.perform(post("/api/admin/stock-adjustments").principal(admin).header("Idempotency-Key", "adjust-01")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"partId":"11","quantityDelta":"-2.001","reason":"Kiểm kê"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_LIMIT_EXCEEDED"));

        verify(movements).lockPart(11L);
        verify(movements, never()).updateStock(anyLong(), any());
        verify(movements, never()).insert(any());
        verify(idempotencyStore, never()).save(anyString(), anyString(), anyString(), anyInt(), anyString(), any(), any());
    }

    @Test
    void staffHistoryIsEnrichedAndReadOnly() throws Exception {
        var row = new StockHistory.MovementView("51", "11", "OIL", "RECEIPT", "2.500", "0.000",
                "2.500", "Nhập hàng", null, "7", OffsetDateTime.parse("2026-11-18T00:00:00Z"),
                null, null, "Nhân viên A", null);
        when(staffHistory.list(0, 20, 11L, StockMovement.Type.RECEIPT))
                .thenReturn(new StockHistory.Page(List.of(row), 0, 20, 1, 1));

        mvc.perform(get("/api/staff/stock-movements").principal(staff)
                        .queryParam("partId", "11").queryParam("movementType", "RECEIPT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].actorDisplayName").value("Nhân viên A"))
                .andExpect(jsonPath("$.items[0].partCode").value("OIL"));

        verify(staffHistory).list(0, 20, 11L, StockMovement.Type.RECEIPT);
        verifyNoInteractions(movements, idempotencyStore);
    }

    @Test
    void stockApiReturnsRetryAfterAndDoesNotExposeDatabaseErrors() throws Exception {
        doThrow(new SqlServerWriteTransactions.LockTimeoutException(new RuntimeException("1222")))
                .when(writes).execute(any());
        mvc.perform(post("/api/admin/stock-adjustments").principal(admin).header("Idempotency-Key", "busy-01")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"partId":"11","quantityDelta":"1.000","reason":"Kiểm kê"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));

        reset(writes);
        when(writes.execute(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(movements.lockPart(11L)).thenReturn(java.util.Optional.of(new StockMovementRepository.LockedPart(
                11, "OIL", StockQuantity.fromDatabase(BigDecimal.ZERO))));
        when(movements.insert(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "private SQL Server constraint text"));
        mvc.perform(post("/api/admin/stock-receipts").principal(admin).header("Idempotency-Key", "sql-01")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"partId":"11","quantity":"1.000","reason":"Nhập kho"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_CONFLICT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private SQL"))));
    }

    @Test
    void rejectsBadStockQuantityUnknownFieldsAndMissingKeyBeforeMutation() throws Exception {
        mvc.perform(post("/api/admin/stock-receipts").principal(admin).header("Idempotency-Key", "bad-01")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"partId":"11","quantity":"1.0000","reason":"Nhập kho"}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/stock-adjustments").principal(admin).header("Idempotency-Key", "bad-02")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"partId":"11","quantityDelta":"1.000","reason":"Kiểm kê","stockQuantity":"99"}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/stock-receipts").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""
                                {"partId":"11","quantity":"1.000","reason":"Nhập kho"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(movements, idempotencyStore);
    }

    private static PartView view(String id, String code) {
        return new PartView(id, code, "Dầu nhớt", "Lít", "BOTH", 0, "0.000", true, 0,
                OffsetDateTime.parse("2026-11-18T00:00:00Z"), OffsetDateTime.parse("2026-11-18T00:00:00Z"));
    }

    private static StockMovement movement(long id, StockMovementRepository.NewMovement row) {
        return new StockMovement(id, row.partId(), row.partCode(), row.type(), row.quantityDelta(),
                row.quantityBefore(), row.quantityAfter(), row.reason(), row.note(), row.actorId(),
                OffsetDateTime.parse("2026-11-18T00:00:00Z"), row.repairItemId(), row.originalIssueId());
    }
}
