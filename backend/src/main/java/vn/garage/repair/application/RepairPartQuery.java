package vn.garage.repair.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.inventory.application.RepairStockOperations;
import vn.garage.inventory.domain.StockMovementRepository;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairTotals;

@Service
public class RepairPartQuery {
    private final RepairPartRepository repairs;
    private final RepairOrderStore orders;
    private final RepairStockOperations stock;

    public RepairPartQuery(RepairOrderStore orders, RepairPartRepository repairs, RepairStockOperations stock) {
        this.orders = orders;
        this.repairs = repairs;
        this.stock = stock;
    }

    @Transactional(readOnly = true)
    public Detail detail(long orderId) {
        if (orderId < 1) throw new IllegalArgumentException("INVALID_REPAIR_ORDER_ID");
        RepairOrderStore.RepairOrderContext order = orders.findById(orderId)
                .orElseThrow(RepairPartCommands.RepairOrderNotFound::new);
        List<RepairPartRepository.PartItem> items = repairs.partItems(orderId);
        List<IssueView> issues = stock.issuesForRepairItems(repairs.partItemIds(orderId)).stream()
                .map(RepairPartQuery::issueView).toList();
        RepairTotals.Totals totals = RepairTotals.calculate(repairs.totalLines(orderId).stream()
                .map(line -> new RepairTotals.Line(line.type(), line.quantity(), line.unitPrice(), line.voided())).toList());
        return new Detail(Long.toString(orderId), order.status().name(), order.version(),
                items.stream().map(RepairPartCommands::view).toList(), issues,
                safeLong(totals.laborAmount()), safeLong(totals.partAmount()), safeLong(totals.totalAmount()));
    }

    private static IssueView issueView(StockMovementRepository.IssueReference issue) {
        return new IssueView(Long.toString(issue.id()), Long.toString(issue.repairItemId()),
                Long.toString(issue.partId()), issue.issuedQuantity().toPlainString(),
                issue.remainingQuantity().toPlainString(), issue.createdAt());
    }

    private static long safeLong(java.math.BigDecimal amount) { return amount.longValueExact(); }

    public record Detail(String repairOrderId, String status, long orderVersion,
                         List<RepairPartCommands.PartItemView> partItems, List<IssueView> returnableIssues,
                         long laborAmount, long partAmount, long totalAmount) { }
    public record IssueView(String id, String repairItemId, String partId, String issuedQuantity,
                            String remainingQuantity, java.time.OffsetDateTime createdAt) { }
}
