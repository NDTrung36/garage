package vn.garage.repair.application;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import vn.garage.identity.application.StaffDirectory;
import vn.garage.inventory.application.StockHistory;
import vn.garage.inventory.domain.StockMovement;

@Service
public class StockHistoryQuery {
    private final StockHistory inventory;
    private final RepairItemOrders repairItems;
    private final StaffDirectory staff;

    public StockHistoryQuery(StockHistory inventory, RepairItemOrders repairItems, StaffDirectory staff) {
        this.inventory = inventory;
        this.repairItems = repairItems;
        this.staff = staff;
    }

    public StockHistory.Page list(int page, int size, Long partId, StockMovement.Type type) {
        StockHistory.Page rows = inventory.list(page, size, partId, type);
        Set<Long> itemIds = rows.items().stream().map(StockHistory.MovementView::repairItemId)
                .filter(value -> value != null).map(Long::valueOf).collect(Collectors.toSet());
        Set<Long> actorIds = rows.items().stream().map(StockHistory.MovementView::actorId)
                .map(Long::valueOf).collect(Collectors.toSet());
        Map<Long, Long> orderIds = repairItems.orderIdsByItemIds(itemIds);
        Map<Long, String> displayNames = staff.displayNames(actorIds);
        var enriched = rows.items().stream().map(item -> StockHistory.withHistoryMetadata(item,
                displayNames.get(Long.valueOf(item.actorId())), item.repairItemId() == null
                        ? null : orderIds.get(Long.valueOf(item.repairItemId())))).toList();
        return new StockHistory.Page(enriched, rows.page(), rows.size(), rows.totalElements(), rows.totalPages());
    }

    public interface RepairItemOrders {
        Map<Long, Long> orderIdsByItemIds(Collection<Long> repairItemIds);
    }
}
