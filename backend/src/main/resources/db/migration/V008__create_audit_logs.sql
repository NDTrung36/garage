SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;

CREATE TABLE dbo.audit_logs (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_audit_logs PRIMARY KEY,
    actor_user_id BIGINT NULL,
    action VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id BIGINT NOT NULL,
    reason NVARCHAR(500) NULL,
    before_json NVARCHAR(MAX) NULL,
    after_json NVARCHAR(MAX) NULL,
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_audit_logs_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT FK_audit_logs_actor FOREIGN KEY (actor_user_id) REFERENCES dbo.users(id),
    CONSTRAINT CK_audit_logs_action CHECK (LEN(LTRIM(RTRIM(action))) > 0),
    CONSTRAINT CK_audit_logs_entity_type CHECK (LEN(LTRIM(RTRIM(entity_type))) > 0),
    CONSTRAINT CK_audit_logs_entity_id CHECK (entity_id > 0),
    CONSTRAINT CK_audit_logs_before_json CHECK (before_json IS NULL OR ISJSON(before_json) = 1),
    CONSTRAINT CK_audit_logs_after_json CHECK (after_json IS NULL OR ISJSON(after_json) = 1)
);

CREATE INDEX IX_audit_logs_entity_created
    ON dbo.audit_logs (entity_type, entity_id, created_at DESC, id DESC);
CREATE INDEX IX_audit_logs_actor_created
    ON dbo.audit_logs (actor_user_id, created_at DESC, id DESC)
    WHERE actor_user_id IS NOT NULL;
