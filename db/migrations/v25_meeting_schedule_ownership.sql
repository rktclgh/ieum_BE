-- v25: one-time meetings may start without a schedule, and schedules retain their creator.
-- Forward-only, data-preserving migration. Safe to re-run after a completed application.

BEGIN;

ALTER TABLE meetings
    ALTER COLUMN meeting_at DROP NOT NULL;

ALTER TABLE meeting_schedules
    ADD COLUMN IF NOT EXISTS created_by BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'meeting_schedules'::regclass
          AND conname = 'fk_meeting_schedules_created_by'
    ) THEN
        UPDATE meeting_schedules AS schedule
        SET created_by = meeting.host_id
        FROM meetings AS meeting
        WHERE meeting.meeting_id = schedule.meeting_id
          AND schedule.created_by IS NULL;

        ALTER TABLE meeting_schedules
            ADD CONSTRAINT fk_meeting_schedules_created_by
            FOREIGN KEY (created_by)
            REFERENCES users(user_id)
            ON DELETE SET NULL
            NOT VALID;
    END IF;
END
$$;

ALTER TABLE meeting_schedules
    VALIDATE CONSTRAINT fk_meeting_schedules_created_by;

COMMIT;
