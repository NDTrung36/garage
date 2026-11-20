SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;

CREATE TABLE dbo.stock_movements (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_stock_movements PRIMARY KEY,
    part_id BIGINT NOT NULL,
    repair_item_id BIGINT NULL,
    original_issue_id BIGINT NULL,
    movement_type VARCHAR(15) NOT NULL,
    quantity_delta DECIMAL(18,3) NOT NULL,
    quantity_before DECIMAL(18,3) NOT NULL,
    quantity_after DECIMAL(18,3) NOT NULL,
    reason NVARCHAR(500) NOT NULL,
    note NVARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_stock_movements_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT FK_stock_movements_part FOREIGN KEY (part_id) REFERENCES dbo.parts(id),
    CONSTRAINT FK_stock_movements_repair_item FOREIGN KEY (repair_item_id) REFERENCES dbo.repair_items(id),
    CONSTRAINT FK_stock_movements_original_issue FOREIGN KEY (original_issue_id) REFERENCES dbo.stock_movements(id),
    CONSTRAINT FK_stock_movements_created_by FOREIGN KEY (created_by) REFERENCES dbo.users(id),
    CONSTRAINT CK_stock_movements_type CHECK (movement_type IN ('RECEIPT', 'ISSUE', 'RETURN', 'ADJUSTMENT')),
    CONSTRAINT CK_stock_movements_delta CHECK (quantity_delta <> 0),
    CONSTRAINT CK_stock_movements_balance CHECK (
        quantity_before >= 0 AND quantity_after >= 0
        AND quantity_after = quantity_before + quantity_delta
    ),
    CONSTRAINT CK_stock_movements_reason CHECK (LEN(LTRIM(RTRIM(reason))) BETWEEN 1 AND 500),
    CONSTRAINT CK_stock_movements_note CHECK (
        (movement_type = 'RECEIPT' AND (note IS NULL OR LEN(note) <= 500))
        OR (movement_type <> 'RECEIPT' AND note IS NULL)
    ),
    CONSTRAINT CK_stock_movements_links CHECK (
        (movement_type = 'RECEIPT' AND quantity_delta > 0 AND repair_item_id IS NULL AND original_issue_id IS NULL)
        OR (movement_type = 'ISSUE' AND quantity_delta < 0 AND repair_item_id IS NOT NULL AND original_issue_id IS NULL)
        OR (movement_type = 'RETURN' AND quantity_delta > 0 AND repair_item_id IS NOT NULL AND original_issue_id IS NOT NULL)
        OR (movement_type = 'ADJUSTMENT' AND repair_item_id IS NULL AND original_issue_id IS NULL)
    )
);

CREATE INDEX IX_stock_movements_part_created
    ON dbo.stock_movements (part_id, created_at DESC, id DESC);
CREATE INDEX IX_stock_movements_created
    ON dbo.stock_movements (created_at DESC, id DESC);
CREATE INDEX IX_stock_movements_repair_item
    ON dbo.stock_movements (repair_item_id) WHERE repair_item_id IS NOT NULL;
CREATE INDEX IX_stock_movements_original_issue
    ON dbo.stock_movements (original_issue_id) WHERE original_issue_id IS NOT NULL;
CREATE INDEX IX_stock_movements_created_by
    ON dbo.stock_movements (created_by);
