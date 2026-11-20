SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;

CREATE TABLE dbo.invoices (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_invoices PRIMARY KEY,
    code VARCHAR(24) NOT NULL,
    repair_order_id BIGINT NOT NULL,
    total_amount DECIMAL(18,0) NOT NULL,
    issued_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_invoices_issued_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    payment_status VARCHAR(10) NOT NULL CONSTRAINT DF_invoices_payment_status DEFAULT ('UNPAID'),
    paid_amount DECIMAL(18,0) NULL,
    payment_method VARCHAR(20) NULL,
    paid_by BIGINT NULL,
    paid_at DATETIMEOFFSET(3) NULL,
    version BIGINT NOT NULL CONSTRAINT DF_invoices_version DEFAULT (0),
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_invoices_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_invoices_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT FK_invoices_repair_order FOREIGN KEY (repair_order_id) REFERENCES dbo.repair_orders(id),
    CONSTRAINT FK_invoices_paid_by FOREIGN KEY (paid_by) REFERENCES dbo.users(id),
    CONSTRAINT UX_invoices_code UNIQUE (code),
    CONSTRAINT UX_invoices_repair_order UNIQUE (repair_order_id),
    CONSTRAINT CK_invoices_code CHECK (
        LEN(code) = 16 AND LEFT(code, 4) COLLATE Latin1_General_100_BIN2 = 'INV-'
        AND SUBSTRING(code, 5, 12) COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^A-Z0-9]%'
    ),
    CONSTRAINT CK_invoices_total CHECK (total_amount >= 0),
    CONSTRAINT CK_invoices_version CHECK (version >= 0),
    CONSTRAINT CK_invoices_payment_status CHECK (payment_status IN ('UNPAID', 'PAID')),
    CONSTRAINT CK_invoices_payment_fields CHECK (
        (payment_status = 'UNPAID'
            AND paid_amount IS NULL AND payment_method IS NULL AND paid_by IS NULL AND paid_at IS NULL)
        OR (payment_status = 'PAID'
            AND paid_amount IS NOT NULL AND paid_amount = total_amount
            AND payment_method IS NOT NULL AND paid_by IS NOT NULL AND paid_at IS NOT NULL
            AND (payment_method IN ('CASH', 'BANK_TRANSFER')
                OR (payment_method = 'NO_CHARGE' AND total_amount = 0)))
    )
);

CREATE INDEX IX_invoices_payment_issued
    ON dbo.invoices (payment_status, issued_at DESC, id DESC);
CREATE INDEX IX_invoices_paid_by
    ON dbo.invoices (paid_by) WHERE paid_by IS NOT NULL;
