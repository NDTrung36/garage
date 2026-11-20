package vn.garage.support.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcAuditWriter implements AuditWriter {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public JdbcAuditWriter(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public void write(Long actorUserId, String action, String entityType, long entityId, String reason,
                      Object beforeSnapshot, Object afterSnapshot) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AUDIT_REQUIRES_ACTIVE_TRANSACTION");
        }
        String beforeJson = serialize(beforeSnapshot);
        String afterJson = serialize(afterSnapshot);
        db.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT dbo.audit_logs(actor_user_id,action,entity_type,entity_id,reason,before_json,after_json)
                    VALUES(?,?,?,?,?,?,?)
                    """);
            if (actorUserId == null) statement.setNull(1, Types.BIGINT);
            else statement.setLong(1, actorUserId);
            statement.setString(2, action);
            statement.setString(3, entityType);
            statement.setLong(4, entityId);
            if (reason == null) statement.setNull(5, Types.NVARCHAR);
            else statement.setNString(5, reason);
            if (beforeJson == null) statement.setNull(6, Types.NVARCHAR);
            else statement.setNString(6, beforeJson);
            if (afterJson == null) statement.setNull(7, Types.NVARCHAR);
            else statement.setNString(7, afterJson);
            statement.setQueryTimeout(10);
            return statement;
        });
    }

    private String serialize(Object snapshot) {
        if (snapshot == null) return null;
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("INVALID_AUDIT_SNAPSHOT", error);
        }
    }
}
