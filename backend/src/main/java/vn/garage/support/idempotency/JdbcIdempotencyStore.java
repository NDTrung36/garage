package vn.garage.support.idempotency;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyStore {
    private final JdbcTemplate db;

    public JdbcIdempotencyStore(JdbcTemplate db) {
        this.db = db;
    }

    /** Must be called inside the business transaction. Holds the PK row/key range until commit. */
    public StoredResult lock(String scope, String key, OffsetDateTime now) {
        List<StoredResult> rows = db.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT request_hash,http_status,response_json,expires_at,
                           CASE WHEN expires_at>? THEN 1 ELSE 0 END active
                    FROM dbo.idempotency_records WITH (UPDLOCK,HOLDLOCK,INDEX(PK_idempotency_records))
                    WHERE scope=? AND request_key=?
                    """);
            statement.setObject(1, now);
            statement.setObject(2, scope, Types.VARCHAR);
            statement.setObject(3, key, Types.VARCHAR);
            statement.setQueryTimeout(10);
            return statement;
        }, (statement, row) -> new StoredResult(statement.getString(1), statement.getInt(2),
                statement.getString(3), statement.getObject(4, OffsetDateTime.class), statement.getBoolean(5)));

        if (rows.isEmpty()) return null;
        StoredResult result = rows.getFirst();
        if (result.active()) return result;

        db.update(connection -> {
            var statement = connection.prepareStatement(
                    "DELETE FROM dbo.idempotency_records WHERE scope=? AND request_key=?");
            statement.setObject(1, scope, Types.VARCHAR);
            statement.setObject(2, key, Types.VARCHAR);
            statement.setQueryTimeout(10);
            return statement;
        });
        return null;
    }

    public void save(String scope, String key, String hash, int httpStatus, String responseJson,
                     OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        db.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT dbo.idempotency_records(scope,request_key,request_hash,http_status,response_json,created_at,expires_at)
                    VALUES(?,?,?,?,?,?,?)
                    """);
            statement.setObject(1, scope, Types.VARCHAR);
            statement.setObject(2, key, Types.VARCHAR);
            statement.setObject(3, hash, Types.CHAR);
            statement.setInt(4, httpStatus);
            statement.setNString(5, responseJson);
            statement.setObject(6, createdAt);
            statement.setObject(7, expiresAt);
            statement.setQueryTimeout(10);
            return statement;
        });
    }

    public int deleteExpiredBatch(int batchSize, OffsetDateTime now) {
        if (batchSize < 1 || batchSize > 10_000) throw new IllegalArgumentException("INVALID_CLEANUP_BATCH_SIZE");
        return db.update(connection -> {
            var statement = connection.prepareStatement("""
                    WITH expired AS (
                        SELECT TOP (?) * FROM dbo.idempotency_records
                            WITH (READPAST,ROWLOCK,INDEX(IX_idempotency_records_expiry))
                        WHERE expires_at<=?
                        ORDER BY expires_at
                    )
                    DELETE FROM expired
                    """);
            statement.setInt(1, batchSize);
            statement.setObject(2, now);
            statement.setQueryTimeout(15);
            return statement;
        });
    }

    public record StoredResult(String requestHash, int httpStatus, String responseJson,
                               OffsetDateTime expiresAt, boolean active) { }
}
