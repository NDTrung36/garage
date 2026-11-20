package vn.garage.support.audit;

public interface AuditWriter {
    void write(Long actorUserId, String action, String entityType, long entityId, String reason,
               Object beforeSnapshot, Object afterSnapshot);
}
