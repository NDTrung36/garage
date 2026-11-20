package vn.garage.billing.infrastructure;

import java.time.OffsetDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.garage.billing.domain.InvoicePayment;
import vn.garage.billing.domain.InvoiceStore;

@Repository
public class JdbcInvoiceStore implements InvoiceStore {
    private static final String SELECT_HEADER = """
            SELECT id,code,repair_order_id,total_amount,payment_status,issued_at
            """;
    private static final String SELECT_DETAILS = SELECT_HEADER + ",version,paid_amount,payment_method,paid_by,paid_at";
    private static final RowMapper<Invoice> ROW = (rs, row) -> new Invoice(
            rs.getLong("id"), rs.getString("code"), rs.getLong("total_amount"),
            rs.getString("payment_status"), rs.getObject("issued_at", OffsetDateTime.class));
    private static final RowMapper<ListEntry> ENTRY = (rs, row) ->
            new ListEntry(ROW.mapRow(rs, row), rs.getLong("repair_order_id"));
    private final JdbcTemplate db;

    public JdbcInvoiceStore(JdbcTemplate db) { this.db = db; }

    @Override
    public Optional<Invoice> findByRepairOrder(long repairOrderId) {
        return db.query("""
                SELECT id,code,total_amount,payment_status,issued_at
                FROM dbo.invoices WHERE repair_order_id=?
                """, ROW, repairOrderId).stream().findFirst();
    }

    @Override
    public Optional<Details> findById(long id) {
        return db.query(SELECT_DETAILS + " FROM dbo.invoices WHERE id=?",
                JdbcInvoiceStore::mapDetails, id).stream().findFirst();
    }

    @Override
    public Optional<Details> lockById(long id) {
        return db.query(SELECT_DETAILS + """
                 FROM dbo.invoices WITH (UPDLOCK,HOLDLOCK,INDEX(PK_invoices)) WHERE id=?
                """, JdbcInvoiceStore::mapDetails, id).stream().findFirst();
    }

    @Override
    public Details updatePayment(long id, long expectedVersion, InvoicePayment payment, OffsetDateTime updatedAt) {
        String method = payment.method() == null ? null : payment.method().name();
        return db.queryForObject("""
                UPDATE dbo.invoices
                SET payment_status=?,paid_amount=?,payment_method=?,paid_by=?,paid_at=?,
                    updated_at=?,version=version+1
                OUTPUT INSERTED.id,INSERTED.code,INSERTED.repair_order_id,INSERTED.total_amount,
                    INSERTED.payment_status,INSERTED.issued_at,INSERTED.version,INSERTED.paid_amount,
                    INSERTED.payment_method,INSERTED.paid_by,INSERTED.paid_at
                WHERE id=? AND version=?
                """, JdbcInvoiceStore::mapDetails, payment.status().name(), payment.amount(), method,
                payment.paidBy(), payment.paidAt(), updatedAt, id, expectedVersion);
    }

    private static Details mapDetails(ResultSet rs, int row) throws SQLException {
        var amount = rs.getBigDecimal("paid_amount");
        return new Details(ENTRY.mapRow(rs, row), rs.getLong("version"), amount == null ? null : amount.longValueExact(),
                rs.getString("payment_method"), rs.getObject("paid_by", Long.class),
                rs.getObject("paid_at", OffsetDateTime.class));
    }

    @Override
    public List<ListEntry> list(PaymentStatus status, int page, int size) {
        String sql = SELECT_HEADER + " FROM dbo.invoices";
        String order = " ORDER BY issued_at DESC,id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        long offset = (long) page * size;
        return status == null
                ? db.query(sql + order, ENTRY, offset, size)
                : db.query(sql + " WHERE payment_status=?" + order, ENTRY, status.name(), offset, size);
    }

    @Override
    public long count(PaymentStatus status) {
        String sql = "SELECT COUNT_BIG(*) FROM dbo.invoices";
        return status == null
                ? db.queryForObject(sql, Long.class)
                : db.queryForObject(sql + " WHERE payment_status=?", Long.class, status.name());
    }

    @Override
    public Invoice insertUnpaid(long repairOrderId, String code, long totalAmount, OffsetDateTime issuedAt) {
        try {
            return db.queryForObject("""
                    INSERT dbo.invoices(repair_order_id,code,total_amount,issued_at,payment_status)
                    OUTPUT INSERTED.id,INSERTED.code,INSERTED.total_amount,INSERTED.payment_status,INSERTED.issued_at
                    VALUES(?,?,?,?,'UNPAID')
                    """, ROW, repairOrderId, code, totalAmount, issuedAt);
        } catch (DuplicateKeyException duplicate) {
            for (Throwable cause = duplicate; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && (sql.getErrorCode() == 2601 || sql.getErrorCode() == 2627)
                        && sql.getMessage().contains("'UX_invoices_code'")) {
                    throw new CodeCollision(duplicate);
                }
            }
            throw duplicate;
        }
    }
}
