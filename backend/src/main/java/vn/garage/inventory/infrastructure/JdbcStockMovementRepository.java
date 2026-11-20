package vn.garage.inventory.infrastructure;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.garage.inventory.domain.StockMovement;
import vn.garage.inventory.domain.StockMovementRepository;
import vn.garage.inventory.domain.StockQuantity;

@Repository
public class JdbcStockMovementRepository implements StockMovementRepository {
    private static final String MOVEMENT_COLUMNS = "m.id,m.part_id,p.code,m.repair_item_id,m.original_issue_id,"
            + "m.movement_type,m.quantity_delta,m.quantity_before,m.quantity_after,m.reason,m.note,"
            + "m.created_by,m.created_at";
    private static final RowMapper<StockMovement> MOVEMENT = (rs, row) -> new StockMovement(
            rs.getLong("id"), rs.getLong("part_id"), rs.getString("code"),
            StockMovement.Type.valueOf(rs.getString("movement_type")),
            StockQuantity.fromDatabase(rs.getBigDecimal("quantity_delta")),
            StockQuantity.fromDatabase(rs.getBigDecimal("quantity_before")),
            StockQuantity.fromDatabase(rs.getBigDecimal("quantity_after")), rs.getString("reason"),
            rs.getString("note"), rs.getLong("created_by"),
            rs.getObject("created_at", OffsetDateTime.class), nullableLong(rs, "repair_item_id"),
            nullableLong(rs, "original_issue_id"));

    private final JdbcTemplate db;

    public JdbcStockMovementRepository(JdbcTemplate db) { this.db = db; }

    @Override
    public Optional<LockedPart> lockPart(long partId) {
        return db.query("""
                SELECT id,code,name,unit,vehicle_type,reference_price,active,stock_quantity
                FROM dbo.parts WITH (UPDLOCK,HOLDLOCK)
                WHERE id=?
                """, (rs, row) -> new LockedPart(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("unit"), rs.getString("vehicle_type"), rs.getLong("reference_price"),
                rs.getBoolean("active"), StockQuantity.fromDatabase(rs.getBigDecimal("stock_quantity"))), partId)
                .stream().findFirst();
    }

    @Override
    public void updateStock(long partId, StockQuantity stockQuantity) {
        int changed = db.update("""
                UPDATE dbo.parts
                SET stock_quantity=?,version=version+1,
                    updated_at=TODATETIMEOFFSET(SYSUTCDATETIME(),'+00:00')
                WHERE id=?
                """, stockQuantity.value(), partId);
        if (changed != 1) throw new IllegalStateException("PART_NOT_FOUND");
    }

    @Override
    public StockMovement insert(NewMovement movement) {
        return db.query(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT dbo.stock_movements(part_id,repair_item_id,original_issue_id,movement_type,
                        quantity_delta,quantity_before,quantity_after,reason,note,created_by)
                    OUTPUT INSERTED.id,INSERTED.created_at
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """);
            statement.setLong(1, movement.partId());
            setNullableLong(statement, 2, movement.repairItemId());
            setNullableLong(statement, 3, movement.originalIssueId());
            statement.setObject(4, movement.type().name(), Types.VARCHAR);
            statement.setBigDecimal(5, movement.quantityDelta().value());
            statement.setBigDecimal(6, movement.quantityBefore().value());
            statement.setBigDecimal(7, movement.quantityAfter().value());
            statement.setNString(8, movement.reason());
            if (movement.note() == null) statement.setNull(9, Types.NVARCHAR);
            else statement.setNString(9, movement.note());
            statement.setLong(10, movement.actorId());
            return statement;
        }, (rs, row) -> new StockMovement(rs.getLong("id"), movement.partId(),
                movement.partCode(), movement.type(), movement.quantityDelta(),
                movement.quantityBefore(), movement.quantityAfter(), movement.reason(), movement.note(),
                movement.actorId(), rs.getObject("created_at", OffsetDateTime.class),
                movement.repairItemId(), movement.originalIssueId())).getFirst();
    }

    @Override
    public MovementPage list(int page, int size, Long partId, StockMovement.Type type) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> filterValues = new ArrayList<>();
        if (partId != null) { where.append(" AND m.part_id=?"); filterValues.add(partId); }
        if (type != null) { where.append(" AND m.movement_type=?"); filterValues.add(type.name()); }
        Long total = db.queryForObject("SELECT COUNT_BIG(*) FROM dbo.stock_movements m" + where,
                Long.class, filterValues.toArray());
        List<Object> pageValues = new ArrayList<>(filterValues);
        pageValues.add((long) page * size);
        pageValues.add(size);
        List<StockMovement> items = db.query("SELECT " + MOVEMENT_COLUMNS
                        + " FROM dbo.stock_movements m JOIN dbo.parts p ON p.id=m.part_id" + where
                        + " ORDER BY m.created_at DESC,m.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                MOVEMENT, pageValues.toArray());
        return new MovementPage(items, total == null ? 0 : total);
    }

    @Override
    public Optional<StockMovement> findIssue(long issueId) {
        return db.query("SELECT " + MOVEMENT_COLUMNS + " FROM dbo.stock_movements m JOIN dbo.parts p ON p.id=m.part_id"
                        + " WHERE m.id=? AND m.movement_type='ISSUE'",
                MOVEMENT, issueId).stream().findFirst();
    }

    @Override
    public Optional<StockMovement> lockIssue(long issueId) {
        return db.query("SELECT " + MOVEMENT_COLUMNS + " FROM dbo.stock_movements m WITH (UPDLOCK,HOLDLOCK)"
                        + " JOIN dbo.parts p ON p.id=m.part_id WHERE m.id=? AND m.movement_type='ISSUE'",
                MOVEMENT, issueId).stream().findFirst();
    }

    @Override
    public StockQuantity returnedQuantity(long issueId) {
        java.math.BigDecimal quantity = db.queryForObject("""
                SELECT COALESCE(SUM(quantity_delta),0)
                FROM dbo.stock_movements
                WHERE original_issue_id=? AND movement_type='RETURN'
                """, java.math.BigDecimal.class, issueId);
        return StockQuantity.fromDatabase(quantity);
    }

    @Override
    public List<IssueReference> issuesForRepairItems(Collection<Long> repairItemIds) {
        if (repairItemIds == null || repairItemIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(repairItemIds.size(), "?"));
        return db.query("""
                SELECT i.id,i.part_id,i.repair_item_id,ABS(i.quantity_delta) issued_quantity,
                    ABS(i.quantity_delta)-COALESCE(SUM(r.quantity_delta),0) remaining_quantity,i.created_at
                FROM dbo.stock_movements i
                LEFT JOIN dbo.stock_movements r ON r.original_issue_id=i.id AND r.movement_type='RETURN'
                WHERE i.movement_type='ISSUE' AND i.repair_item_id IN (
                """ + placeholders + ") GROUP BY i.id,i.part_id,i.repair_item_id,i.quantity_delta,i.created_at"
                        + " ORDER BY i.created_at DESC,i.id DESC",
                (rs, row) -> new IssueReference(rs.getLong("id"), rs.getLong("part_id"),
                        rs.getLong("repair_item_id"), StockQuantity.fromDatabase(rs.getBigDecimal("issued_quantity")),
                        StockQuantity.fromDatabase(rs.getBigDecimal("remaining_quantity")),
                        rs.getObject("created_at", OffsetDateTime.class)), repairItemIds.toArray());
    }

    @Override
    public List<ItemNet> netForRepairItems(List<Long> repairItemIds) {
        if (repairItemIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(repairItemIds.size(), "?"));
        return db.query("""
                SELECT repair_item_id,part_id,SUM(-quantity_delta) net_quantity
                FROM dbo.stock_movements
                WHERE movement_type IN ('ISSUE','RETURN') AND repair_item_id IN (
                """ + placeholders + ") GROUP BY repair_item_id,part_id",
                (rs, row) -> new ItemNet(rs.getLong("repair_item_id"), rs.getLong("part_id"),
                        rs.getBigDecimal("net_quantity")), repairItemIds.toArray());
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }
}
