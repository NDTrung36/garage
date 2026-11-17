INSERT INTO dbo.garage_settings (
    id, timezone, slot_minutes, car_capacity, motorbike_capacity,
    minimum_lead_minutes, maximum_advance_days, generated_through
) VALUES (1, 'Asia/Ho_Chi_Minh', 60, 1, 2, 60, 14, NULL);

INSERT INTO dbo.working_hours (day_of_week, opens_at, closes_at)
VALUES
    (1, '08:00:00', '12:00:00'), (1, '13:00:00', '17:00:00'),
    (2, '08:00:00', '12:00:00'), (2, '13:00:00', '17:00:00'),
    (3, '08:00:00', '12:00:00'), (3, '13:00:00', '17:00:00'),
    (4, '08:00:00', '12:00:00'), (4, '13:00:00', '17:00:00'),
    (5, '08:00:00', '12:00:00'), (5, '13:00:00', '17:00:00'),
    (6, '08:00:00', '12:00:00'), (6, '13:00:00', '17:00:00');
