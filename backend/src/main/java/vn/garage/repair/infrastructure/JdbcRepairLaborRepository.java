package vn.garage.repair.infrastructure;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.garage.repair.domain.RepairLaborRepository;

@Repository
public class JdbcRepairLaborRepository implements RepairLaborRepository {
    private static final org.springframework.jdbc.core.RowMapper<LaborItem> ROW = (rs, row) -> {
        Long serviceId = rs.getObject("service_id", Long.class);
        return new LaborItem(rs.getLong("id"), rs.getLong("repair_order_id"), serviceId,
                rs.getString("description"), rs.getString("unit_snapshot"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"), rs.getObject("voided_at") != null);
    };

    private final JdbcTemplate db;

    public JdbcRepairLaborRepository(JdbcTemplate db) { this.db = db; }

    @Override
    public Optional<LaborItem> findLaborForUpdate(long repairOrderId, long itemId) {
        return db.query("""
                SELECT id,repair_order_id,service_id,description,unit_snapshot,quantity,unit_price,voided_at
                FROM dbo.repair_items WITH (UPDLOCK,HOLDLOCK,INDEX(PK_repair_items))
                WHERE repair_order_id=? AND id=? AND item_type='LABOR'
                """, ROW, repairOrderId, itemId).stream().findFirst();
    }

    @Override
    public List<LaborItem> laborItems(long repairOrderId) {
        return db.query("""
                SELECT id,repair_order_id,service_id,description,unit_snapshot,quantity,unit_price,voided_at
                FROM dbo.repair_items
                WHERE repair_order_id=? AND item_type='LABOR' AND voided_at IS NULL
                ORDER BY id
                """, ROW, repairOrderId);
    }

    @Override
    public LaborItem insertLabor(long repairOrderId, Long serviceId, String description, String unit,
                                 BigDecimal quantity, long unitPrice) {
        List<Long> ids = db.query(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT dbo.repair_items(repair_order_id,item_type,service_id,description,unit_snapshot,quantity,unit_price)
                    OUTPUT INSERTED.id VALUES(?,'LABOR',?,?,?,?,?)
                    """);
            statement.setLong(1, repairOrderId);
            if (serviceId == null) statement.setNull(2, Types.BIGINT);
            else statement.setLong(2, serviceId);
            statement.setNString(3, description);
            statement.setNString(4, unit);
            statement.setBigDecimal(5, quantity);
            statement.setLong(6, unitPrice);
            return statement;
        }, (rs, row) -> rs.getLong(1));
        if (ids.isEmpty()) throw new IllegalStateException("LABOR_INSERT_RETURNED_NO_ID");
        return findLaborForUpdate(repairOrderId, ids.getFirst()).orElseThrow(LaborItemNotFound::new);
    }

    @Override
    public LaborItem updateLabor(long repairOrderId, long itemId, String description,
                                 BigDecimal quantity, long unitPrice) {
        int changed = db.update("""
                UPDATE dbo.repair_items
                SET description=?,quantity=?,unit_price=?,updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')
                WHERE repair_order_id=? AND id=? AND item_type='LABOR' AND voided_at IS NULL
                """, description, quantity, unitPrice, repairOrderId, itemId);
        if (changed != 1) throw new LaborItemNotFound();
        return findLaborForUpdate(repairOrderId, itemId).orElseThrow(LaborItemNotFound::new);
    }

    @Override
    public boolean voidLabor(long repairOrderId, long itemId, long actorId, OffsetDateTime voidedAt) {
        return db.update("""
                UPDATE dbo.repair_items
                SET voided_at=?,voided_by=?,updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')
                WHERE repair_order_id=? AND id=? AND item_type='LABOR'
                  AND voided_at IS NULL AND voided_by IS NULL
                """, voidedAt, actorId, repairOrderId, itemId) == 1;
    }
}
