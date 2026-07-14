-- Additive v18 -> v20 report-target upgrade. v19 is reserved for the stacked admin-report work.
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

COMMIT;
