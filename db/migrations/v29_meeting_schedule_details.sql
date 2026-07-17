-- Additive display details for schedules created from a meeting chat room.
-- Legacy meeting and recurring schedule rows intentionally remain nullable.
BEGIN;

ALTER TABLE public.meeting_schedules
    ADD COLUMN IF NOT EXISTS title VARCHAR(100),
    ADD COLUMN IF NOT EXISTS location_name VARCHAR(200);

COMMIT;
