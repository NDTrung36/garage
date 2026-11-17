SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;

CREATE TABLE dbo.garage_settings (
    id TINYINT NOT NULL CONSTRAINT PK_garage_settings PRIMARY KEY,
    timezone VARCHAR(50) NOT NULL,
    slot_minutes INT NOT NULL,
    car_capacity INT NOT NULL,
    motorbike_capacity INT NOT NULL,
    minimum_lead_minutes INT NOT NULL,
    maximum_advance_days INT NOT NULL,
    generated_through DATE NULL,
    version BIGINT NOT NULL CONSTRAINT DF_garage_settings_version DEFAULT (0),
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_garage_settings_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_garage_settings_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT CK_garage_settings_singleton CHECK (id = 1),
    CONSTRAINT CK_garage_settings_timezone CHECK (timezone = 'Asia/Ho_Chi_Minh'),
    CONSTRAINT CK_garage_settings_slot_minutes CHECK (slot_minutes > 0),
    CONSTRAINT CK_garage_settings_capacities CHECK (car_capacity >= 0 AND motorbike_capacity >= 0),
    CONSTRAINT CK_garage_settings_booking_window CHECK (minimum_lead_minutes > 0 AND maximum_advance_days > 0),
    CONSTRAINT CK_garage_settings_version CHECK (version >= 0)
);

CREATE TABLE dbo.working_hours (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_working_hours PRIMARY KEY,
    day_of_week TINYINT NOT NULL,
    opens_at TIME(0) NOT NULL,
    closes_at TIME(0) NOT NULL,
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_working_hours_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_working_hours_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT UX_working_hours_day_open UNIQUE (day_of_week, opens_at),
    CONSTRAINT CK_working_hours_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT CK_working_hours_interval CHECK (opens_at < closes_at)
);

CREATE TABLE dbo.calendar_exceptions (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_calendar_exceptions PRIMARY KEY,
    closed_date DATE NOT NULL,
    reason NVARCHAR(500) NOT NULL,
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_calendar_exceptions_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_calendar_exceptions_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT UX_calendar_exceptions_date UNIQUE (closed_date),
    CONSTRAINT CK_calendar_exceptions_reason CHECK (LEN(LTRIM(RTRIM(reason))) > 0)
);

CREATE TABLE dbo.appointment_slots (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_appointment_slots PRIMARY KEY,
    starts_at DATETIMEOFFSET(3) NOT NULL,
    ends_at DATETIMEOFFSET(3) NOT NULL,
    vehicle_type VARCHAR(10) NOT NULL,
    capacity INT NOT NULL,
    closed BIT NOT NULL CONSTRAINT DF_appointment_slots_closed DEFAULT (0),
    version BIGINT NOT NULL CONSTRAINT DF_appointment_slots_version DEFAULT (0),
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_appointment_slots_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_appointment_slots_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT UX_appointment_slots_start_type UNIQUE (starts_at, vehicle_type),
    CONSTRAINT CK_appointment_slots_interval CHECK (starts_at < ends_at),
    CONSTRAINT CK_appointment_slots_type CHECK (vehicle_type IN ('CAR', 'MOTORBIKE')),
    CONSTRAINT CK_appointment_slots_capacity CHECK (capacity >= 0),
    CONSTRAINT CK_appointment_slots_version CHECK (version >= 0)
);

CREATE TABLE dbo.bookings (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_bookings PRIMARY KEY,
    code VARCHAR(24) NOT NULL,
    customer_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    slot_id BIGINT NULL,
    source VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,
    vehicle_type_snapshot VARCHAR(10) NOT NULL,
    contact_name_snapshot NVARCHAR(100) NOT NULL,
    contact_phone_snapshot VARCHAR(20) NOT NULL,
    plate_snapshot NVARCHAR(20) NOT NULL,
    plate_normalized_snapshot NVARCHAR(20) NOT NULL,
    brand_snapshot NVARCHAR(100) NULL,
    model_snapshot NVARCHAR(100) NULL,
    customer_note NVARCHAR(1000) NULL,
    created_by BIGINT NULL,
    status_reason NVARCHAR(500) NULL,
    version BIGINT NOT NULL CONSTRAINT DF_bookings_version DEFAULT (0),
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_bookings_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    updated_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_bookings_updated_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    CONSTRAINT UX_bookings_code UNIQUE (code),
    CONSTRAINT FK_bookings_customer FOREIGN KEY (customer_id) REFERENCES dbo.customers(id),
    CONSTRAINT FK_bookings_vehicle_customer FOREIGN KEY (vehicle_id, customer_id)
        REFERENCES dbo.vehicles(id, customer_id),
    CONSTRAINT FK_bookings_slot FOREIGN KEY (slot_id) REFERENCES dbo.appointment_slots(id),
    CONSTRAINT FK_bookings_created_by FOREIGN KEY (created_by) REFERENCES dbo.users(id),
    CONSTRAINT CK_bookings_source CHECK (source IN ('ONLINE', 'WALK_IN')),
    CONSTRAINT CK_bookings_status CHECK (status IN ('CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT CK_bookings_source_slot CHECK (
        (source = 'ONLINE' AND slot_id IS NOT NULL)
        OR (source = 'WALK_IN' AND slot_id IS NULL AND created_by IS NOT NULL
            AND status IN ('CHECKED_IN', 'COMPLETED'))
    ),
    CONSTRAINT CK_bookings_vehicle_type CHECK (vehicle_type_snapshot IN ('CAR', 'MOTORBIKE')),
    CONSTRAINT CK_bookings_version CHECK (version >= 0),
    CONSTRAINT CK_bookings_code CHECK (LEN(LTRIM(RTRIM(code))) > 0),
    CONSTRAINT CK_bookings_contact_name CHECK (LEN(LTRIM(RTRIM(contact_name_snapshot))) >= 2),
    CONSTRAINT CK_bookings_phone CHECK (
        DATALENGTH(contact_phone_snapshot) = 12 AND LEFT(contact_phone_snapshot, 3) = '+84'
        AND SUBSTRING(contact_phone_snapshot, 4, 9) COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^0-9]%'
    ),
    CONSTRAINT CK_bookings_plate CHECK (LEN(LTRIM(RTRIM(plate_snapshot))) > 0),
    CONSTRAINT CK_bookings_normalized_plate CHECK (
        LEN(plate_normalized_snapshot) > 0
        AND plate_normalized_snapshot COLLATE Latin1_General_100_BIN2 NOT LIKE N'%[^A-Z0-9]%'
    )
);

CREATE UNIQUE INDEX UX_bookings_slot_plate_occupied
ON dbo.bookings (slot_id, plate_normalized_snapshot)
WHERE slot_id IS NOT NULL AND status IN ('CONFIRMED', 'CHECKED_IN', 'COMPLETED');
CREATE INDEX IX_bookings_customer_created ON dbo.bookings (customer_id, created_at DESC, id DESC);
CREATE INDEX IX_bookings_vehicle_customer ON dbo.bookings (vehicle_id, customer_id);
CREATE INDEX IX_bookings_slot_status ON dbo.bookings (slot_id, status);
CREATE INDEX IX_bookings_created_by ON dbo.bookings (created_by) WHERE created_by IS NOT NULL;

CREATE TABLE dbo.booking_services (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_booking_services PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    service_name_snapshot NVARCHAR(150) NOT NULL,
    reference_price_snapshot DECIMAL(18,0) NOT NULL,
    CONSTRAINT FK_booking_services_booking FOREIGN KEY (booking_id) REFERENCES dbo.bookings(id),
    CONSTRAINT FK_booking_services_service FOREIGN KEY (service_id) REFERENCES dbo.services(id),
    CONSTRAINT UX_booking_services_booking_service UNIQUE (booking_id, service_id),
    CONSTRAINT CK_booking_services_name CHECK (LEN(LTRIM(RTRIM(service_name_snapshot))) > 0),
    CONSTRAINT CK_booking_services_price CHECK (reference_price_snapshot >= 0)
);
CREATE INDEX IX_booking_services_service ON dbo.booking_services (service_id);

CREATE TABLE dbo.idempotency_records (
    scope VARCHAR(100) COLLATE Latin1_General_100_BIN2 NOT NULL,
    request_key VARCHAR(100) COLLATE Latin1_General_100_BIN2 NOT NULL,
    request_hash CHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_json NVARCHAR(MAX) NOT NULL,
    created_at DATETIMEOFFSET(3) NOT NULL CONSTRAINT DF_idempotency_records_created_at
        DEFAULT (TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),
    expires_at DATETIMEOFFSET(3) NOT NULL,
    CONSTRAINT PK_idempotency_records PRIMARY KEY (scope, request_key),
    CONSTRAINT CK_idempotency_records_scope CHECK (LEN(LTRIM(RTRIM(scope))) > 0),
    CONSTRAINT CK_idempotency_records_key CHECK (LEN(LTRIM(RTRIM(request_key))) > 0),
    CONSTRAINT CK_idempotency_records_hash CHECK (
        LEN(request_hash) = 64
        AND request_hash COLLATE Latin1_General_100_BIN2 NOT LIKE '%[^0-9a-f]%'
    ),
    CONSTRAINT CK_idempotency_records_http_status CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT CK_idempotency_records_json CHECK (ISJSON(response_json) = 1),
    CONSTRAINT CK_idempotency_records_expiry CHECK (expires_at > created_at)
);
CREATE INDEX IX_idempotency_records_expiry ON dbo.idempotency_records (expires_at);
