-- Forward-only report AI result and cumulative sanction ledger upgrade.
-- This migration preserves all existing rows and never rebuilds application tables.
BEGIN;

DO $create_sanction_review_status$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'sanction_review_status') THEN
        CREATE TYPE sanction_review_status AS ENUM ('pending_review', 'confirmed', 'dismissed', 'not_required');
    END IF;
END;
$create_sanction_review_status$;

ALTER TABLE user_sanctions
    ADD COLUMN IF NOT EXISTS duration_minutes INTEGER,
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revoked_by BIGINT,
    ADD COLUMN IF NOT EXISTS review_status sanction_review_status;

UPDATE user_sanctions
SET duration_minutes = GREATEST(
        1,
        CEIL(EXTRACT(EPOCH FROM (ends_at - starts_at)) / 60.0)::INTEGER
    )
WHERE sanction_type = 'temporary'
  AND duration_minutes IS NULL;

UPDATE user_sanctions
SET review_status = CASE
        WHEN decision_source = 'ai_recommendation' THEN 'pending_review'::sanction_review_status
        ELSE 'not_required'::sanction_review_status
    END
WHERE review_status IS NULL;

UPDATE user_sanctions
SET revoked_at = released_at,
    revoked_by = released_by
WHERE released_at IS NOT NULL
  AND revoked_at IS NULL;

DO $add_revoked_by_fk$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_sanctions_revoked_by') THEN
        ALTER TABLE user_sanctions
            ADD CONSTRAINT fk_user_sanctions_revoked_by
            FOREIGN KEY (revoked_by) REFERENCES users(user_id) NOT VALID;
    END IF;
    ALTER TABLE user_sanctions VALIDATE CONSTRAINT fk_user_sanctions_revoked_by;
END;
$add_revoked_by_fk$;

DO $validate_review_status_not_null$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_user_sanctions_review_status_nn') THEN
        ALTER TABLE user_sanctions
            ADD CONSTRAINT ck_user_sanctions_review_status_nn
            CHECK (review_status IS NOT NULL) NOT VALID;
    END IF;
    ALTER TABLE user_sanctions VALIDATE CONSTRAINT ck_user_sanctions_review_status_nn;
END;
$validate_review_status_not_null$;

ALTER TABLE user_sanctions
    ALTER COLUMN review_status SET DEFAULT 'not_required',
    ALTER COLUMN review_status SET NOT NULL;

ALTER TABLE user_sanctions
    DROP CONSTRAINT IF EXISTS ck_user_sanctions_review_status_nn;

DO $add_sanction_constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_user_sanctions_duration') THEN
        ALTER TABLE user_sanctions
            ADD CONSTRAINT ck_user_sanctions_duration CHECK (
                (sanction_type = 'temporary' AND duration_minutes IS NOT NULL AND duration_minutes > 0 AND ends_at IS NOT NULL)
                OR (sanction_type = 'permanent' AND duration_minutes IS NULL)
            ) NOT VALID;
        ALTER TABLE user_sanctions VALIDATE CONSTRAINT ck_user_sanctions_duration;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_user_sanctions_review_status') THEN
        ALTER TABLE user_sanctions
            ADD CONSTRAINT ck_user_sanctions_review_status CHECK (
                (
                    decision_source = 'ai_recommendation'
                    AND (report_id IS NOT NULL OR revoked_at IS NOT NULL)
                    AND review_status IN ('pending_review', 'confirmed', 'dismissed')
                )
                OR (decision_source = 'admin' AND review_status = 'not_required')
            ) NOT VALID;
        ALTER TABLE user_sanctions VALIDATE CONSTRAINT ck_user_sanctions_review_status;
    END IF;
END;
$add_sanction_constraints$;

-- Migrations in this repository run as one transaction, so CONCURRENTLY is unavailable.
-- Schedule v23 in a bounded deployment window when these tables become large.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_sanctions_ai_report
    ON user_sanctions (report_id)
    WHERE decision_source = 'ai_recommendation'
      AND report_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_sanctions_effective
    ON user_sanctions (user_id, ends_at, sanction_id)
    WHERE revoked_at IS NULL
      AND review_status <> 'dismissed';

DO $add_report_completed_projection_constraint$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_reports_ai_completed_projection') THEN
        ALTER TABLE reports
            ADD CONSTRAINT ck_reports_ai_completed_projection CHECK (
                ai_review_state <> 'completed'
                OR (
                    ai_decision IS NOT NULL
                    AND ai_confidence IS NOT NULL
                    AND ai_reason IS NOT NULL
                    AND ai_model_version IS NOT NULL
                    AND ai_policy_set_hash IS NOT NULL
                    AND ai_reviewed_at IS NOT NULL
                    AND ai_review_result IS NOT NULL
                )
            ) NOT VALID;
        ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_ai_completed_projection;
    END IF;
END;
$add_report_completed_projection_constraint$;

COMMIT;
