-- Additive report-target upgrade from the canonical v18 baseline.
-- target_type survives target deletion while the selected FK may become NULL through ON DELETE SET NULL.
BEGIN;

CREATE TYPE report_target_type AS ENUM ('message', 'answer');

ALTER TABLE reports
    ADD COLUMN target_type report_target_type NOT NULL DEFAULT 'message',
    ADD COLUMN answer_id BIGINT REFERENCES answers(answer_id) ON DELETE SET NULL,
    ALTER COLUMN reported_user_id DROP NOT NULL,
    ADD CONSTRAINT ck_reports_target_xor
        CHECK (
            (target_type = 'message' AND answer_id IS NULL)
            OR (target_type = 'answer' AND message_id IS NULL)
        ) NOT VALID,
    ADD CONSTRAINT ck_reports_message_reported_user
        CHECK (target_type <> 'message' OR reported_user_id IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_reports_answer_manual_only
        CHECK (target_type <> 'answer' OR ai_review_state = 'cancelled') NOT VALID;

CREATE INDEX idx_reports_answer
    ON reports(answer_id)
    WHERE answer_id IS NOT NULL;

ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_target_xor;
ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_message_reported_user;
ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_answer_manual_only;

CREATE OR REPLACE FUNCTION enforce_report_target_integrity()
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
                USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_required';
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
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_required';
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
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_required';
            END IF;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' AND (
        (NEW.target_type = 'message' AND NEW.message_id IS NULL)
        OR (NEW.target_type = 'answer' AND NEW.answer_id IS NULL)
    ) THEN
        RAISE EXCEPTION 'report selected target is required at creation'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_required';
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

CREATE TRIGGER trg_reports_target_integrity
BEFORE INSERT OR UPDATE OF target_type, message_id, answer_id, reported_user_id
ON reports
FOR EACH ROW
EXECUTE FUNCTION enforce_report_target_integrity();

COMMIT;
