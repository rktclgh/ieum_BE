BEGIN;

ALTER TABLE ai_question_tasks
    DROP CONSTRAINT IF EXISTS ck_ai_question_tasks_embedding_model;

ALTER TABLE ai_question_tasks
    ADD COLUMN legacy_embedding_model_migrated BOOLEAN NOT NULL DEFAULT FALSE;

-- Incomplete legacy work must be re-embedded. Completed legacy answers keep
-- their original model provenance so notification ACK updates remain possible.
UPDATE ai_question_tasks
   SET embedding = NULL,
       embedding_model = NULL
 WHERE embedding_model IS DISTINCT FROM 'gemini-embedding-2'
   AND status <> 'completed';

-- This marker is set only for rows that predate v15. The trigger below freezes
-- it after the migration so future jobs cannot opt into the legacy exception.
UPDATE ai_question_tasks
   SET legacy_embedding_model_migrated = TRUE
 WHERE status = 'completed'
   AND embedding_model = 'gemini-embedding-001';

ALTER TABLE ai_question_tasks
    ADD CONSTRAINT ck_ai_question_tasks_embedding_model
    CHECK (
        (
            NOT legacy_embedding_model_migrated
            AND (embedding_model IS NULL OR embedding_model = 'gemini-embedding-2')
        )
        OR (
            legacy_embedding_model_migrated
            AND status = 'completed'
            AND embedding_model = 'gemini-embedding-001'
        )
    );

CREATE OR REPLACE FUNCTION protect_ai_question_task_legacy_embedding_marker()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.legacy_embedding_model_migrated)
        OR (TG_OP = 'UPDATE'
            AND NEW.legacy_embedding_model_migrated IS DISTINCT FROM OLD.legacy_embedding_model_migrated) THEN
        RAISE EXCEPTION
            'ck_ai_question_tasks_embedding_model: legacy migration marker is immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_ai_question_tasks_embedding_model';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_ai_question_tasks_legacy_embedding_marker
    BEFORE INSERT OR UPDATE OF legacy_embedding_model_migrated ON ai_question_tasks
    FOR EACH ROW EXECUTE FUNCTION protect_ai_question_task_legacy_embedding_marker();

ALTER TABLE ai_question_tasks
    ADD COLUMN cancel_requested_at TIMESTAMPTZ,
    ADD COLUMN answer_notification_processed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_ai_question_tasks_answer_notification
    CHECK (answer_notification_processed_at IS NULL OR (status = 'completed' AND answer_id IS NOT NULL));

DROP INDEX IF EXISTS idx_ai_question_tasks_embedding_hnsw;

CREATE INDEX idx_ai_question_tasks_pending_notification
    ON ai_question_tasks(completed_at, question_id)
    INCLUDE (answer_id)
    WHERE status = 'completed'
      AND answer_id IS NOT NULL
      AND answer_notification_processed_at IS NULL;

ALTER TABLE notifications
    ADD COLUMN answer_is_ai BOOLEAN,
    ADD COLUMN event_key VARCHAR(120),
    ADD CONSTRAINT ck_notifications_answer_is_ai
        CHECK (answer_is_ai IS NULL OR type = 'question'::notification_type);

CREATE UNIQUE INDEX uidx_notifications_user_event_key
    ON notifications(user_id, event_key)
    WHERE event_key IS NOT NULL;

COMMIT;
