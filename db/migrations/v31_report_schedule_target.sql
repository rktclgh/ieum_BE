-- Add schedule reports to the existing manual administrator-review flow.
-- v30 must be applied in an earlier committed transaction because PostgreSQL delays enum-value visibility.
BEGIN;

ALTER TABLE public.reports
    ADD COLUMN IF NOT EXISTS schedule_id BIGINT;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.reports'::regclass
          AND conname = 'fk_reports_schedule'
    ) THEN
        ALTER TABLE public.reports
            ADD CONSTRAINT fk_reports_schedule
            FOREIGN KEY (schedule_id)
            REFERENCES public.meeting_schedules(schedule_id)
            ON DELETE SET NULL
            NOT VALID;
    END IF;
END
$migration$;

ALTER TABLE public.reports
    VALIDATE CONSTRAINT fk_reports_schedule;

ALTER TABLE public.reports
    DROP CONSTRAINT IF EXISTS ck_reports_target_xor;

ALTER TABLE public.reports
    ADD CONSTRAINT ck_reports_target_xor
        CHECK (
            (target_type = 'message' AND answer_id IS NULL AND schedule_id IS NULL)
            OR (target_type = 'answer' AND message_id IS NULL AND schedule_id IS NULL)
            OR (target_type = 'schedule' AND message_id IS NULL AND answer_id IS NULL)
        ) NOT VALID,
    ADD CONSTRAINT ck_reports_schedule_manual_only
        CHECK (target_type <> 'schedule' OR ai_review_state = 'cancelled') NOT VALID,
    ADD CONSTRAINT ck_reports_schedule_reported_user
        CHECK (target_type <> 'schedule' OR reported_user_id IS NOT NULL) NOT VALID;

ALTER TABLE public.reports
    VALIDATE CONSTRAINT ck_reports_target_xor;
ALTER TABLE public.reports
    VALIDATE CONSTRAINT ck_reports_schedule_manual_only;
ALTER TABLE public.reports
    VALIDATE CONSTRAINT ck_reports_schedule_reported_user;

CREATE OR REPLACE FUNCTION public.enforce_report_target_integrity()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_answer_is_ai BOOLEAN;
    v_answer_author_id BIGINT;
    v_schedule_creator_id BIGINT;
    v_allowed_target_delete BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.target_type IS DISTINCT FROM OLD.target_type THEN
            RAISE EXCEPTION 'report target type is immutable'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
        END IF;

        IF NEW.message_id IS DISTINCT FROM OLD.message_id THEN
            v_allowed_target_delete :=
                OLD.target_type = 'message'
                AND OLD.message_id IS NOT NULL
                AND NEW.message_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM public.messages WHERE message_id = OLD.message_id
                );
            IF NOT v_allowed_target_delete THEN
                RAISE EXCEPTION 'report message target may only be cleared by target deletion'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
            END IF;
        END IF;

        IF NEW.answer_id IS DISTINCT FROM OLD.answer_id THEN
            v_allowed_target_delete :=
                OLD.target_type = 'answer'
                AND OLD.answer_id IS NOT NULL
                AND NEW.answer_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM public.answers WHERE answer_id = OLD.answer_id
                );
            IF NOT v_allowed_target_delete THEN
                RAISE EXCEPTION 'report answer target may only be cleared by target deletion'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
            END IF;
        END IF;

        IF NEW.schedule_id IS DISTINCT FROM OLD.schedule_id THEN
            v_allowed_target_delete :=
                OLD.target_type = 'schedule'
                AND OLD.schedule_id IS NOT NULL
                AND NEW.schedule_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM public.meeting_schedules WHERE schedule_id = OLD.schedule_id
                );
            IF NOT v_allowed_target_delete THEN
                RAISE EXCEPTION 'report schedule target may only be cleared by target deletion'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
            END IF;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' AND (
        (NEW.target_type = 'message' AND NEW.message_id IS NULL)
        OR (NEW.target_type = 'answer' AND NEW.answer_id IS NULL)
        OR (NEW.target_type = 'schedule' AND NEW.schedule_id IS NULL)
    ) THEN
        RAISE EXCEPTION 'report selected target is required at creation'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
    END IF;

    IF NEW.target_type = 'answer' AND NEW.answer_id IS NOT NULL THEN
        SELECT is_ai, author_id
          INTO v_answer_is_ai, v_answer_author_id
          FROM public.answers
         WHERE answer_id = NEW.answer_id;

        IF FOUND AND (
            (v_answer_is_ai AND (v_answer_author_id IS NOT NULL OR NEW.reported_user_id IS NOT NULL))
            OR (
                NOT v_answer_is_ai
                AND (v_answer_author_id IS NULL OR NEW.reported_user_id IS DISTINCT FROM v_answer_author_id)
            )
        ) THEN
            RAISE EXCEPTION 'reported user must match the answer author semantics'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_answer_reported_user';
        END IF;
    END IF;

    IF NEW.target_type = 'schedule' AND NEW.schedule_id IS NOT NULL THEN
        SELECT created_by
          INTO v_schedule_creator_id
          FROM public.meeting_schedules
         WHERE schedule_id = NEW.schedule_id;

        IF FOUND AND (
            v_schedule_creator_id IS NULL
            OR NEW.reported_user_id IS DISTINCT FROM v_schedule_creator_id
        ) THEN
            RAISE EXCEPTION 'reported user must match the schedule creator'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_schedule_reported_user';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_reports_target_integrity ON public.reports;
CREATE TRIGGER trg_reports_target_integrity
BEFORE INSERT OR UPDATE OF target_type, message_id, answer_id, schedule_id, reported_user_id
ON public.reports
FOR EACH ROW
EXECUTE FUNCTION public.enforce_report_target_integrity();

CREATE INDEX IF NOT EXISTS idx_reports_schedule
    ON public.reports(schedule_id)
    WHERE schedule_id IS NOT NULL;

COMMIT;
