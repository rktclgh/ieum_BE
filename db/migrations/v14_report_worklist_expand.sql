-- One-shot v13 -> v14 upgrade. The deployment DB owner records successful versions and
-- must not rerun this script after COMMIT; a failure rolls back this entire transaction.
BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE ai_report_decision AS ENUM ('suspend', 'hold', 'normal');

ALTER TABLE reports
    ADD COLUMN context_hash CHAR(64),
    ADD COLUMN ai_review_state ai_job_status,
    ADD COLUMN ai_review_attempt_id UUID,
    ADD COLUMN ai_attempts SMALLINT,
    ADD COLUMN ai_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN ai_lease_until TIMESTAMPTZ,
    ADD COLUMN ai_locked_by VARCHAR(120),
    ADD COLUMN ai_last_error_code VARCHAR(80),
    ADD COLUMN ai_last_error_message VARCHAR(500),
    ADD COLUMN ai_decision ai_report_decision,
    ADD COLUMN ai_policy_set_hash CHAR(64),
    ADD COLUMN ai_review_result JSONB;

UPDATE reports
   SET context_hash = encode(digest(convert_to(COALESCE(context_snapshot, 'null'::jsonb)::text, 'UTF8'), 'sha256'), 'hex'),
       ai_review_state = CASE
           WHEN status = 'pending' THEN 'pending'::ai_job_status
           ELSE 'dead'::ai_job_status
       END,
       ai_attempts = 0,
       ai_next_attempt_at = CASE
           WHEN status = 'pending' THEN created_at
           ELSE NULL
       END,
       ai_last_error_code = CASE
           WHEN status = 'pending' THEN NULL
           ELSE 'LEGACY_NON_PENDING_REPORT'
       END;

ALTER TABLE reports
    ALTER COLUMN context_hash SET NOT NULL,
    ALTER COLUMN ai_review_state SET NOT NULL,
    ALTER COLUMN ai_review_state SET DEFAULT 'pending',
    ALTER COLUMN ai_attempts SET NOT NULL,
    ALTER COLUMN ai_attempts SET DEFAULT 0,
    ALTER COLUMN ai_next_attempt_at SET DEFAULT now(),
    ADD CONSTRAINT ck_reports_context_hash
        CHECK (context_hash ~ '^[0-9a-f]{64}$') NOT VALID,
    ADD CONSTRAINT ck_reports_ai_attempts
        CHECK (ai_attempts BETWEEN 0 AND 5) NOT VALID,
    ADD CONSTRAINT ck_reports_ai_processing_lease
        CHECK (ai_review_state <> 'processing' OR (
            ai_review_attempt_id IS NOT NULL
            AND ai_lease_until IS NOT NULL
            AND ai_locked_by IS NOT NULL
        )) NOT VALID,
    ADD CONSTRAINT ck_reports_ai_due_work
        CHECK (ai_review_state NOT IN ('pending', 'retry') OR ai_next_attempt_at IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_reports_ai_completed
        CHECK (ai_review_state <> 'completed' OR (
            ai_decision IS NOT NULL
            AND ai_review_result IS NOT NULL
            AND ai_reviewed_at IS NOT NULL
        )) NOT VALID,
    ADD CONSTRAINT ck_reports_ai_dead
        CHECK (ai_review_state <> 'dead' OR ai_last_error_code IS NOT NULL) NOT VALID;

CREATE INDEX idx_reports_ai_due
    ON reports(ai_next_attempt_at, created_at, report_id)
    WHERE ai_review_state IN ('pending', 'retry');

CREATE INDEX idx_reports_ai_expired_lease
    ON reports(ai_lease_until, report_id)
    WHERE ai_review_state = 'processing';

ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_context_hash;
ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_ai_attempts;
ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_ai_processing_lease;
ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_ai_due_work;
ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_ai_completed;
ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_ai_dead;

COMMIT;
