SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;

CREATE TABLE dbo.parts (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_parts PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    name NVARCHAR(150) NOT NULL,
    unit NVARCHAR(30) NOT NULL,
    vehicle_type VARCHAR(10) NOT NULL,
    reference_price DECIMAL(18,0) NOT NULL,
    stock_quantity DECIMAL(18,3) NOT NULL CONSTRAINT DF_parts_stock_quantity DEFAULT (0),
    active BIT NOT NULL CONSTRAINT DF_parts_active DEFAULT (1),
    version BIGINT NOT NULL CONSTRAINT DF_parts_version DEFAULT (0),
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_parts_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_parts_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT UX_parts_code UNIQUE (code),
    CONSTRAINT CK_parts_code CHECK (LEN(LTRIM(RTRIM(code))) > 0),
    CONSTRAINT CK_parts_name CHECK (LEN(LTRIM(RTRIM(name))) > 0),
    CONSTRAINT CK_parts_unit CHECK (LEN(LTRIM(RTRIM(unit))) > 0),
    CONSTRAINT CK_parts_vehicle_type CHECK (vehicle_type IN ('CAR', 'MOTORBIKE', 'BOTH')),
    CONSTRAINT CK_parts_reference_price CHECK (reference_price >= 0),
    CONSTRAINT CK_parts_stock_quantity CHECK (stock_quantity >= 0),
    CONSTRAINT CK_parts_version CHECK (version >= 0)
);
