SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;

CREATE TABLE dbo.repair_orders (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_repair_orders PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT DF_repair_orders_status DEFAULT ('IN_PROGRESS'),
    odometer_km INT NULL,
    condition_notes NVARCHAR(2000) NULL,
    internal_notes NVARCHAR(2000) NULL,
    received_by BIGINT NOT NULL,
    received_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_repair_orders_received_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    completed_by BIGINT NULL,
    completed_at DATETIMEOFFSET(3) NULL,
    version BIGINT NOT NULL CONSTRAINT DF_repair_orders_version DEFAULT (0),
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_repair_orders_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_repair_orders_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT FK_repair_orders_booking FOREIGN KEY (booking_id) REFERENCES dbo.bookings(id),
    CONSTRAINT FK_repair_orders_received_by FOREIGN KEY (received_by) REFERENCES dbo.users(id),
    CONSTRAINT FK_repair_orders_completed_by FOREIGN KEY (completed_by) REFERENCES dbo.users(id),
    CONSTRAINT UX_repair_orders_booking UNIQUE (booking_id),
    CONSTRAINT CK_repair_orders_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT CK_repair_orders_odometer CHECK (odometer_km IS NULL OR odometer_km >= 0),
    CONSTRAINT CK_repair_orders_version CHECK (version >= 0),
    CONSTRAINT CK_repair_orders_completion CHECK (
        (status = 'IN_PROGRESS' AND completed_by IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_by IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX IX_repair_orders_status_received
    ON dbo.repair_orders (status, received_at DESC, id DESC);
CREATE INDEX IX_repair_orders_received_by
    ON dbo.repair_orders (received_by);
CREATE INDEX IX_repair_orders_completed_by
    ON dbo.repair_orders (completed_by) WHERE completed_by IS NOT NULL;

CREATE TABLE dbo.repair_items (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_repair_items PRIMARY KEY,
    repair_order_id BIGINT NOT NULL,
    item_type VARCHAR(10) NOT NULL,
    service_id BIGINT NULL,
    part_id BIGINT NULL,
    description NVARCHAR(200) NOT NULL,
    unit_snapshot NVARCHAR(30) NOT NULL,
    quantity DECIMAL(18,3) NOT NULL,
    unit_price DECIMAL(18,0) NOT NULL,
    voided_at DATETIMEOFFSET(3) NULL,
    voided_by BIGINT NULL,
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_repair_items_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_repair_items_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT FK_repair_items_repair_order FOREIGN KEY (repair_order_id) REFERENCES dbo.repair_orders(id),
    CONSTRAINT FK_repair_items_service FOREIGN KEY (service_id) REFERENCES dbo.services(id),
    CONSTRAINT FK_repair_items_part FOREIGN KEY (part_id) REFERENCES dbo.parts(id),
    CONSTRAINT FK_repair_items_voided_by FOREIGN KEY (voided_by) REFERENCES dbo.users(id),
    CONSTRAINT CK_repair_items_type CHECK (item_type IN ('LABOR', 'PART')),
    CONSTRAINT CK_repair_items_description CHECK (LEN(LTRIM(RTRIM(description))) > 0),
    CONSTRAINT CK_repair_items_unit CHECK (LEN(LTRIM(RTRIM(unit_snapshot))) > 0),
    CONSTRAINT CK_repair_items_price CHECK (unit_price >= 0),
    CONSTRAINT CK_repair_items_kind CHECK (
        (item_type = 'LABOR' AND part_id IS NULL AND quantity > 0)
        OR (item_type = 'PART' AND part_id IS NOT NULL AND service_id IS NULL AND quantity >= 0)
    ),
    CONSTRAINT CK_repair_items_void CHECK (
        (voided_at IS NULL AND voided_by IS NULL)
        OR (item_type = 'LABOR' AND voided_at IS NOT NULL AND voided_by IS NOT NULL)
    )
);

CREATE UNIQUE INDEX UX_repair_items_order_part
    ON dbo.repair_items (repair_order_id, part_id)
    WHERE part_id IS NOT NULL;
CREATE INDEX IX_repair_items_order ON dbo.repair_items (repair_order_id, id);
CREATE INDEX IX_repair_items_part ON dbo.repair_items (part_id) WHERE part_id IS NOT NULL;
CREATE INDEX IX_repair_items_service ON dbo.repair_items (service_id) WHERE service_id IS NOT NULL;
CREATE INDEX IX_repair_items_voided_by ON dbo.repair_items (voided_by) WHERE voided_by IS NOT NULL;
