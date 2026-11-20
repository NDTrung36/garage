package vn.garage.repair.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.garage.repair.domain.RepairPartRepository;
import vn.garage.repair.domain.RepairTotals;
import vn.garage.repair.application.StockHistoryQuery;

@Repository
public class JdbcRepairPartRepository implements StockHistoryQuery.RepairItemOrders, RepairPartRepository {
    private static final RowMapper<PartItem> PART_ITEM = (rs, row) -> new PartItem(rs.getLong("id"),
            rs.getLong("repair_order_id"), rs.getLong("part_id"), rs.getString("description"),
            rs.getString("unit_snapshot"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"));
    private final JdbcTemplate db;

    public JdbcRepairPartRepository(JdbcTemplate db) { this.db = db; }

    @Override
    public Map<Long, Long> orderIdsByItemIds(Collection<Long> repairItemIds) {
        if (repairItemIds == null || repairItemIds.isEmpty()) return Map.of();
        String placeholders = repairItemIds.stream().map(ignored -> "?").collect(Collectors.joining(","));
        return db.query("SELECT id,repair_order_id FROM dbo.repair_items WHERE id IN (" + placeholders + ")",
                ps -> {
                    int index = 1;
                    for (Long id : repairItemIds) ps.setLong(index++, id);
                }, (rs, row) -> Map.entry(rs.getLong("id"), rs.getLong("repair_order_id")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public java.util.Optional<PartItem> findPartItem(long repairOrderId, long partId) {
        return db.query("""
                SELECT id,repair_order_id,part_id,description,unit_snapshot,quantity,unit_price
                FROM dbo.repair_items WHERE repair_order_id=? AND part_id=? AND item_type='PART'
                """, PART_ITEM, repairOrderId, partId).stream().findFirst();
    }

    @Override
    public java.util.Optional<PartItem> findPartItemById(long repairOrderId, long itemId) {
        return db.query("""
                SELECT id,repair_order_id,part_id,description,unit_snapshot,quantity,unit_price
                FROM dbo.repair_items WHERE repair_order_id=? AND id=? AND item_type='PART'
                """, PART_ITEM, repairOrderId, itemId).stream().findFirst();
    }

    @Override
    public PartItem insertPartItem(long repairOrderId, long partId, String description, String unit, long unitPrice) {
        return db.query(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT dbo.repair_items(repair_order_id,item_type,part_id,description,unit_snapshot,quantity,unit_price)
                    OUTPUT INSERTED.id
                    VALUES(?,'PART',?,?,?,0,?)
                    """);
            statement.setLong(1, repairOrderId);
            statement.setLong(2, partId);
            statement.setNString(3, description);
            statement.setNString(4, unit);
            statement.setLong(5, unitPrice);
            return statement;
        }, (rs, row) -> rs.getLong(1)).stream().findFirst()
                .flatMap(id -> findPartItemById(repairOrderId, id)).orElseThrow();
    }

    @Override
    public PartItem updatePartQuantity(long repairOrderId, long itemId, java.math.BigDecimal quantity) {
        int rows = db.update("""
                UPDATE dbo.repair_items SET quantity=?,updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')
                WHERE repair_order_id=? AND id=? AND item_type='PART'
                """, quantity, repairOrderId, itemId);
        if (rows != 1) throw new RepairPartRepository.PartItemNotFound();
        return findPartItemById(repairOrderId, itemId).orElseThrow(RepairPartRepository.PartItemNotFound::new);
    }

    @Override
    public PartItem updatePartPrice(long repairOrderId, long itemId, long unitPrice) {
        int rows = db.update("""
                UPDATE dbo.repair_items SET unit_price=?,updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')
                WHERE repair_order_id=? AND id=? AND item_type='PART'
                """, unitPrice, repairOrderId, itemId);
        if (rows != 1) throw new RepairPartRepository.PartItemNotFound();
        return findPartItemById(repairOrderId, itemId).orElseThrow(RepairPartRepository.PartItemNotFound::new);
    }

    @Override
    public long incrementOrderVersion(long repairOrderId) {
        Long version = db.queryForObject("""
                UPDATE dbo.repair_orders
                SET version=version+1,updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')
                OUTPUT INSERTED.version WHERE id=?
                """, Long.class, repairOrderId);
        if (version == null) throw new RepairPartRepository.RepairOrderNotFound();
        return version;
    }

    @Override
    public List<PartItem> partItems(long repairOrderId) {
        return db.query("""
                SELECT id,repair_order_id,part_id,description,unit_snapshot,quantity,unit_price
                FROM dbo.repair_items WHERE repair_order_id=? AND item_type='PART' ORDER BY id
                """, PART_ITEM, repairOrderId);
    }

    @Override
    public List<Long> partItemIds(long repairOrderId) {
        return db.query("SELECT id FROM dbo.repair_items WHERE repair_order_id=? AND item_type='PART' ORDER BY id",
                (rs, row) -> rs.getLong(1), repairOrderId);
    }

    @Override
    public List<TotalLine> totalLines(long repairOrderId) {
        return db.query("""
                SELECT item_type,quantity,unit_price,voided_at
                FROM dbo.repair_items WHERE repair_order_id=?
                """, (rs, row) -> new TotalLine(RepairTotals.Type.valueOf(rs.getString("item_type")),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"), rs.getObject("voided_at") != null),
                repairOrderId);
    }

}
