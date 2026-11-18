ALTER TABLE dbo.services
    ADD version BIGINT NOT NULL CONSTRAINT DF_services_version DEFAULT (0);

ALTER TABLE dbo.appointment_slots
    ADD manual_closed BIT NOT NULL CONSTRAINT DF_appointment_slots_manual_closed DEFAULT (0),
        capacity_override INT NULL;
GO

ALTER TABLE dbo.services
    ADD CONSTRAINT CK_services_version CHECK (version >= 0);
ALTER TABLE dbo.appointment_slots
    ADD CONSTRAINT CK_appointment_slots_capacity_override
        CHECK (capacity_override IS NULL OR capacity_override BETWEEN 0 AND 100);
