package vn.garage.repair.api;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.inventory.application.StockHistory;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.repair.application.RepairPartCommands;
import vn.garage.repair.application.RepairPartQuery;
import vn.garage.support.idempotency.IdempotencyExecutor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RepairPartsApiTest {
    private RepairPartCommands commands;
    private RepairPartQuery query;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken staff;

    @BeforeEach
    void setUp() {
        commands = mock(RepairPartCommands.class);
        query = mock(RepairPartQuery.class);
        mvc = MockMvcBuilders.standaloneSetup(new RepairPartsController(commands, query))
                .setControllerAdvice(new RepairErrors()).build();
        var principal = new GaragePrincipal(41, "staff", "Nhân viên thử", "STAFF", 0, "hash");
        staff = new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities());
    }

    @Test
    void issuesThroughStaffPrincipalAndReturnsNoStoreContract() throws Exception {
        when(commands.issue(7, 9, "0.500", "issue-key", 41, "STAFF"))
                .thenReturn(new IdempotencyExecutor.Result<>(201,
                        new RepairPartCommands.CommandResponse(new StockHistory.MovementView("1", "9", "OIL", "ISSUE",
                                "-0.500", "2.000", "1.500", "Xuất phụ tùng cho phiếu sửa chữa", null, "41",
                                OffsetDateTime.parse("2026-11-18T10:00:00Z"), "12", null, null, null), "1.500",
                                new RepairPartCommands.PartItemView("12", "9", "Dầu nhớt", "Lít", "0.500", 101, 51),
                                4, 51), false));
        mvc.perform(post("/api/staff/repair-orders/7/part-issues").principal(staff)
                        .header("Idempotency-Key", "issue-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partId\":\"9\",\"quantity\":\"0.500\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.movement.movementType").value("ISSUE"))
                .andExpect(jsonPath("$.partItem.quantity").value("0.500"))
                .andExpect(jsonPath("$.repairTotal").value(51));
    }

    @Test
    void returnsDetailedPartLinesAndReturnableIssueSnapshot() throws Exception {
        when(query.detail(7)).thenReturn(new RepairPartQuery.Detail("7", "IN_PROGRESS", 3,
                List.of(new RepairPartCommands.PartItemView("12", "9", "Dầu nhớt", "Lít", "0.000", 101, 0)),
                List.of(new RepairPartQuery.IssueView("23", "12", "9", "0.500", "0.000",
                        OffsetDateTime.parse("2026-11-18T10:00:00Z"))), 0, 0, 0));
        mvc.perform(get("/api/staff/repair-orders/7/parts").principal(staff))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orderVersion").value(3))
                .andExpect(jsonPath("$.partItems[0].quantity").value("0.000"))
                .andExpect(jsonPath("$.returnableIssues[0].remainingQuantity").value("0.000"));
    }

    @Test
    void mapsBusinessConflictsAndRejectsBadInputBeforeCallingCommands() throws Exception {
        when(commands.returnPart(7, 23, "0.500", "return-key", 41, "STAFF"))
                .thenThrow(new RepairStockOperations.ReturnExceedsIssue());
        mvc.perform(post("/api/staff/repair-orders/7/part-returns").principal(staff)
                        .header("Idempotency-Key", "return-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalIssueId\":\"23\",\"quantity\":\"0.500\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETURN_EXCEEDS_ISSUE"));

        mvc.perform(post("/api/staff/repair-orders/7/part-issues").principal(staff)
                        .header("Idempotency-Key", "bad").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partId\":\"9\",\"quantity\":\"0.500\",\"stockQuantity\":\"99\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/staff/repair-orders/7/part-issues").principal(staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partId\":\"9\",\"quantity\":\"0.500\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verify(commands, never()).issue(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void priceUpdatePassesExpectedOrderVersionAndMapsStaleOrder() throws Exception {
        mvc.perform(patch("/api/staff/repair-orders/7/part-items/12/price").principal(staff)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":202,\"reason\":\" \",\"expectedVersion\":4}"))
                .andExpect(status().isBadRequest());
        when(commands.updatePrice(7, 12, 202, "Cập nhật giá", 4, 41, "STAFF"))
                .thenThrow(new RepairPartCommands.StaleVersion());
        mvc.perform(patch("/api/staff/repair-orders/7/part-items/12/price").principal(staff)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":202,\"reason\":\"Cập nhật giá\",\"expectedVersion\":4}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        verify(commands).updatePrice(7, 12, 202, "Cập nhật giá", 4, 41, "STAFF");
    }
}
