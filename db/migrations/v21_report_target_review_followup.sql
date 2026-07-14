-- Forward-only review follow-up for databases that already applied v20.
-- Normalize the answer FK name and trigger diagnostics without replaying the v20 table upgrade.
BEGIN;

DO $rename_answer_fk$
DECLARE
    v_has_legacy_name BOOLEAN;
    v_has_canonical_name BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.reports'::regclass
          AND contype = 'f'
          AND conname = 'reports_answer_id_fkey'
    ) INTO v_has_legacy_name;

    SELECT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.reports'::regclass
          AND contype = 'f'
          AND conname = 'fk_reports_answer'
    ) INTO v_has_canonical_name;

    IF v_has_legacy_name AND v_has_canonical_name THEN
        RAISE EXCEPTION 'both legacy and canonical answer report foreign keys exist';
    ELSIF v_has_legacy_name THEN
        ALTER TABLE public.reports
            RENAME CONSTRAINT reports_answer_id_fkey TO fk_reports_answer;
    ELSIF NOT v_has_canonical_name THEN
        RAISE EXCEPTION 'answer report foreign key is missing';
    END IF;
END;
$rename_answer_fk$;

CREATE OR REPLACE FUNCTION public.enforce_report_target_integrity()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_answer_is_ai BOOLEAN;
    v_answer_author_id BIGINT;
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
                    SELECT 1 FROM messages WHERE message_id = OLD.message_id
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
                    SELECT 1 FROM answers WHERE answer_id = OLD.answer_id
                );
            IF NOT v_allowed_target_delete THEN
                RAISE EXCEPTION 'report answer target may only be cleared by target deletion'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
            END IF;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' AND (
        (NEW.target_type = 'message' AND NEW.message_id IS NULL)
        OR (NEW.target_type = 'answer' AND NEW.answer_id IS NULL)
    ) THEN
        RAISE EXCEPTION 'report selected target is required at creation'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
    END IF;

    IF NEW.target_type = 'answer' AND NEW.answer_id IS NOT NULL THEN
        SELECT is_ai, author_id
          INTO v_answer_is_ai, v_answer_author_id
          FROM answers
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

    RETURN NEW;
END;
$function$;

COMMIT;
