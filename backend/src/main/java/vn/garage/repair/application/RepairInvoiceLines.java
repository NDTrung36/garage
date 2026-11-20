package vn.garage.repair.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.repair.domain.RepairLaborRepository;
import vn.garage.repair.domain.RepairOrderStore;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairStatus;
import vn.garage.repair.domain.RepairTotals;

@Service
public class RepairInvoiceLines {
    private final RepairOrderStore orders;
    private final RepairLaborRepository labor;
    private final RepairPartRepository parts;

    public RepairInvoiceLines(RepairOrderStore orders, RepairLaborRepository labor, RepairPartRepository parts) {
        this.orders = orders;
        this.labor = labor;
        this.parts = parts;
    }

    @Transactional(readOnly = true)
    public List<Line> forCompletedOrder(long orderId) {
        var order = orders.findById(orderId).orElseThrow(RepairCompletionService.InconsistentRepair::new);
        if (order.status() != RepairStatus.COMPLETED) {
            throw new RepairCompletionService.InconsistentRepair();
        }
        // Both repositories return rows in ascending ID order. The labor query excludes voided rows.
        List<Line> lines = new ArrayList<>();
        for (var item : labor.laborItems(orderId)) {
            lines.add(new Line(Long.toString(item.id()), RepairTotals.Type.LABOR, item.description(), item.unit(),
                    item.quantity().setScale(3).toPlainString(), item.unitPrice().longValueExact(),
                    item.lineTotal().longValueExact()));
        }
        for (var item : parts.partItems(orderId)) {
            if (item.quantity().signum() == 0) continue;
            var amount = new RepairTotals.Line(RepairTotals.Type.PART, item.quantity(), item.unitPrice(), false)
                    .lineTotal();
            lines.add(new Line(Long.toString(item.id()), RepairTotals.Type.PART, item.description(), item.unit(),
                    item.quantity().setScale(3).toPlainString(), item.unitPrice().longValueExact(), amount.longValueExact()));
        }
        return List.copyOf(lines);
    }

    public record Line(String id, RepairTotals.Type type, String description, String unit,
                       String quantity, long unitPrice, long lineTotal) { }
}
