SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;

CREATE TABLE dbo.users (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_users PRIMARY KEY,
    username NVARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name NVARCHAR(100) NOT NULL,
    role VARCHAR(10) NOT NULL,
    active BIT NOT NULL CONSTRAINT DF_users_active DEFAULT (1),
    auth_version BIGINT NOT NULL CONSTRAINT DF_users_auth_version DEFAULT (0),
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_users_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_users_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT UX_users_username UNIQUE (username),
    CONSTRAINT CK_users_role CHECK (role IN ('ADMIN', 'STAFF')),
    CONSTRAINT CK_users_auth_version CHECK (auth_version >= 0),
    CONSTRAINT CK_users_username CHECK (LEN(LTRIM(RTRIM(username))) > 0),
    CONSTRAINT CK_users_display_name CHECK (LEN(LTRIM(RTRIM(display_name))) > 0),
    CONSTRAINT CK_users_password_hash CHECK (LEN(LTRIM(RTRIM(password_hash))) > 0)
);

CREATE TABLE dbo.customers (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_customers PRIMARY KEY,
    full_name NVARCHAR(100) NOT NULL,
    phone_normalized VARCHAR(20) NOT NULL,
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_customers_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_customers_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT UX_customers_phone_normalized UNIQUE (phone_normalized),
    CONSTRAINT CK_customers_full_name CHECK (LEN(LTRIM(RTRIM(full_name))) >= 2),
    CONSTRAINT CK_customers_phone_normalized CHECK (
        DATALENGTH(phone_normalized) = 12 AND LEFT(phone_normalized, 3) = '+84'
        AND SUBSTRING(phone_normalized, 4, 9) COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^0-9]%'
    )
);

CREATE TABLE dbo.vehicles (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_vehicles PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    vehicle_type VARCHAR(10) NOT NULL,
    plate_display NVARCHAR(20) NOT NULL,
    plate_normalized NVARCHAR(20) NOT NULL,
    brand NVARCHAR(100) NULL,
    model NVARCHAR(100) NULL,
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_vehicles_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_vehicles_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT FK_vehicles_customer FOREIGN KEY (customer_id) REFERENCES dbo.customers(id),
    CONSTRAINT UX_vehicles_customer_plate UNIQUE (customer_id, plate_normalized),
    CONSTRAINT UX_vehicles_id_customer UNIQUE (id, customer_id),
    CONSTRAINT CK_vehicles_type CHECK (vehicle_type IN ('CAR', 'MOTORBIKE')),
    CONSTRAINT CK_vehicles_plate_display CHECK (LEN(LTRIM(RTRIM(plate_display))) > 0),
    CONSTRAINT CK_vehicles_plate_normalized CHECK (
        LEN(plate_normalized) > 0
        AND plate_normalized COLLATE Latin1_General_100_BIN2 NOT LIKE N'%[^A-Z0-9]%'
    )
);

CREATE TABLE dbo.services (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_services PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    name NVARCHAR(150) NOT NULL,
    description NVARCHAR(1000) NULL,
    vehicle_type VARCHAR(10) NOT NULL,
    reference_price DECIMAL(18,0) NOT NULL,
    active BIT NOT NULL CONSTRAINT DF_services_active DEFAULT (1),
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_services_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_services_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT UX_services_code UNIQUE (code),
    CONSTRAINT CK_services_vehicle_type CHECK (vehicle_type IN ('CAR', 'MOTORBIKE', 'BOTH')),
    CONSTRAINT CK_services_price CHECK (reference_price >= 0),
    CONSTRAINT CK_services_code CHECK (LEN(LTRIM(RTRIM(code))) > 0),
    CONSTRAINT CK_services_name CHECK (LEN(LTRIM(RTRIM(name))) > 0)
);
