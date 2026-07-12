BEGIN;

ALTER TYPE ai_job_stage ADD VALUE IF NOT EXISTS 'analyzing';
ALTER TYPE ai_job_stage ADD VALUE IF NOT EXISTS 'embedding';
ALTER TYPE ai_job_stage ADD VALUE IF NOT EXISTS 'web_grounding';

ALTER TABLE ai_question_tasks
    ADD COLUMN lease_token UUID,
    ADD COLUMN geo_scope VARCHAR(24),
    ADD COLUMN geo_scope_confidence NUMERIC(5,4),
    ADD COLUMN region_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN answer_outcome VARCHAR(32),
    ADD COLUMN generation_provider VARCHAR(40),
    ADD COLUMN retrieval_config_version VARCHAR(80),
    ADD COLUMN fallback_reason VARCHAR(100),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE ai_question_tasks
   SET lease_token = gen_random_uuid()
 WHERE status = 'processing'
   AND lease_token IS NULL;

UPDATE ai_question_tasks
   SET answer_outcome = 'local_grounded',
       generation_provider = COALESCE(generation_provider, 'legacy_v12'),
       evidence = CASE
           WHEN jsonb_typeof(evidence) = 'array' AND jsonb_array_length(evidence) > 0 THEN evidence
           ELSE '[{"type":"legacy_v12","source":"pre_v13_completed_task"}]'::jsonb
       END
 WHERE status = 'completed'
   AND answer_id IS NOT NULL;

ALTER TABLE ai_question_tasks
    ADD CONSTRAINT ck_ai_question_tasks_geo_scope
        CHECK (geo_scope IS NULL OR geo_scope IN ('general','regional','local','place_specific')) NOT VALID,
    ADD CONSTRAINT ck_ai_question_tasks_geo_confidence
        CHECK (geo_scope_confidence IS NULL OR geo_scope_confidence BETWEEN 0 AND 1) NOT VALID,
    ADD CONSTRAINT ck_ai_question_tasks_region_context
        CHECK (jsonb_typeof(region_context)='object') NOT VALID,
    ADD CONSTRAINT ck_ai_question_tasks_answer_outcome
        CHECK (answer_outcome IS NULL OR answer_outcome IN ('local_grounded','web_grounded','insufficient_evidence')) NOT VALID;

DO $$
DECLARE
    constraint_record RECORD;
    normalized_definition TEXT;
BEGIN
    FOR constraint_record IN
        SELECT conname, oid
          FROM pg_constraint
         WHERE conrelid = 'ai_question_tasks'::regclass
           AND contype = 'c'
    LOOP
        normalized_definition := regexp_replace(pg_get_constraintdef(constraint_record.oid), '\s+', '', 'g');
        IF normalized_definition LIKE '%status=''processing''::ai_job_status%'
           OR normalized_definition LIKE '%status<>''completed''::ai_job_status%' THEN
            EXECUTE format('ALTER TABLE ai_question_tasks DROP CONSTRAINT %I', constraint_record.conname);
        END IF;
    END LOOP;
END
$$;

ALTER TABLE ai_question_tasks
    ADD CONSTRAINT ck_ai_question_tasks_processing_lease
        CHECK ((status='processing') =
            (lease_until IS NOT NULL AND locked_by IS NOT NULL AND lease_token IS NOT NULL)) NOT VALID,
    ADD CONSTRAINT ck_ai_question_tasks_completed
        CHECK (status <> 'completed' OR (
            completed_at IS NOT NULL
            AND embedding IS NOT NULL
            AND embedding_model IS NOT NULL
            AND answer_outcome IS NOT NULL
            AND grounding_status IS NOT NULL
            AND (
                (answer_outcome='insufficient_evidence' AND answer_id IS NULL)
                OR
                (answer_outcome IN ('local_grounded','web_grounded')
                    AND answer_id IS NOT NULL
                    AND generation_provider IS NOT NULL
                    AND generation_model IS NOT NULL
                    AND jsonb_array_length(evidence) > 0)
            )
        )) NOT VALID;

ALTER TABLE knowledge_sources
    ADD COLUMN display_name VARCHAR(200),
    ADD COLUMN status VARCHAR(24),
    ADD COLUMN last_error_code VARCHAR(100),
    ADD COLUMN last_error_message TEXT,
    ADD COLUMN deactivation_reason VARCHAR(200),
    ADD COLUMN ingestion_token UUID,
    ADD COLUMN ingestion_lease_until TIMESTAMPTZ,
    ADD COLUMN geo_scope VARCHAR(24),
    ADD COLUMN region_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN anchor_location GEOGRAPHY(Point,4326),
    ADD COLUMN valid_until TIMESTAMPTZ,
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN created_by VARCHAR(100),
    ADD COLUMN updated_by VARCHAR(100),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE knowledge_sources
   SET display_name = COALESCE(NULLIF(btrim(external_ref), ''), 'source-' || source_id),
       status = CASE WHEN active THEN 'ready' ELSE 'inactive' END;

DO $$
DECLARE
    constraint_record RECORD;
    normalized_definition TEXT;
BEGIN
    FOR constraint_record IN
        SELECT conname, oid
          FROM pg_constraint
         WHERE conrelid = 'knowledge_sources'::regclass
           AND contype = 'c'
    LOOP
        normalized_definition := regexp_replace(pg_get_constraintdef(constraint_record.oid), '\s+', '', 'g');
        IF normalized_definition LIKE '%active%'
           AND normalized_definition LIKE '%deactivated_at%' THEN
            EXECUTE format('ALTER TABLE knowledge_sources DROP CONSTRAINT %I', constraint_record.conname);
        END IF;
    END LOOP;
END
$$;

ALTER TABLE knowledge_sources
    ALTER COLUMN display_name SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ADD CONSTRAINT ck_knowledge_sources_status
        CHECK (status IN ('pending','ready','failed','inactive','admin_suppressed')) NOT VALID,
    ADD CONSTRAINT ck_knowledge_sources_geo_scope
        CHECK (geo_scope IS NULL OR geo_scope IN ('general','regional','local','place_specific')) NOT VALID,
    ADD CONSTRAINT ck_knowledge_sources_region_context
        CHECK (jsonb_typeof(region_context)='object') NOT VALID,
    ADD CONSTRAINT ck_knowledge_sources_metadata
        CHECK (jsonb_typeof(metadata)='object') NOT VALID,
    ADD CONSTRAINT ck_knowledge_sources_ingestion_lease
        CHECK ((status='pending') =
            (ingestion_token IS NOT NULL AND ingestion_lease_until IS NOT NULL)) NOT VALID;

CREATE INDEX idx_knowledge_sources_expired_ingestion
    ON knowledge_sources(ingestion_lease_until, source_id)
    WHERE status='pending';

CREATE OR REPLACE FUNCTION sync_knowledge_source_active_compat()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.active := NEW.status='ready';
    IF NEW.status IN ('inactive','admin_suppressed') THEN
        NEW.deactivated_at := COALESCE(NEW.deactivated_at, now());
    ELSIF NEW.status IN ('pending','ready','failed') THEN
        NEW.deactivated_at := NULL;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_knowledge_source_active_compat
BEFORE INSERT OR UPDATE OF status ON knowledge_sources
FOR EACH ROW EXECUTE FUNCTION sync_knowledge_source_active_compat();

ALTER TABLE knowledge_chunks
    ADD CONSTRAINT uq_knowledge_chunks_source_chunk UNIQUE (source_id, chunk_id);

ALTER TABLE knowledge_relations
    ADD COLUMN evidence_chunk_id BIGINT,
    ADD CONSTRAINT fk_knowledge_relations_same_source_evidence
        FOREIGN KEY (source_id, evidence_chunk_id)
        REFERENCES knowledge_chunks(source_id, chunk_id)
        ON DELETE CASCADE;

UPDATE knowledge_sources ks
   SET status='inactive',
       deactivation_reason='legacy_embedding_model',
       updated_at=now()
 WHERE EXISTS (
       SELECT 1
         FROM knowledge_chunks kc
        WHERE kc.source_id=ks.source_id
          AND kc.embedding_model IS DISTINCT FROM 'gemini-embedding-2'
   );

ALTER TABLE knowledge_chunks
    ADD CONSTRAINT ck_knowledge_chunks_embedding_model
        CHECK (embedding_model='gemini-embedding-2') NOT VALID;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM knowledge_sources
         WHERE external_ref IS NOT NULL
         GROUP BY source_type, external_ref, content_hash
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate knowledge_sources external_ref/content_hash triples block v13; resolve duplicates before creating uidx_knowledge_source_external_hash'
            USING ERRCODE = '23505';
    END IF;
END
$$;

CREATE UNIQUE INDEX uidx_knowledge_source_external_hash
    ON knowledge_sources(source_type, external_ref, content_hash)
    WHERE external_ref IS NOT NULL;

CREATE TABLE ai_report_policy_rules (
    rule_id BIGSERIAL PRIMARY KEY,
    rule_code VARCHAR(100) NOT NULL UNIQUE CHECK (rule_code ~ '^[A-Z0-9][A-Z0-9_-]{2,99}$'),
    title VARCHAR(200) NOT NULL CHECK (btrim(title) <> ''),
    category VARCHAR(100) NOT NULL CHECK (btrim(category) <> ''),
    criteria TEXT NOT NULL CHECK (btrim(criteria) <> ''),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('suspend','hold','normal')),
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('low','medium','high','critical')),
    min_confidence NUMERIC(5,4) NOT NULL CHECK (min_confidence BETWEEN 0 AND 1),
    evidence_types VARCHAR(10) NOT NULL CHECK (evidence_types IN ('text','image','both')),
    priority INTEGER NOT NULL DEFAULT 0 CHECK (priority BETWEEN -1000 AND 1000),
    positive_examples JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(positive_examples)='array'),
    negative_examples JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(negative_examples)='array'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    created_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    updated_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (decision <> 'suspend' OR severity IN ('high','critical'))
);

CREATE INDEX idx_ai_report_policy_rules_snapshot
    ON ai_report_policy_rules(active, priority DESC, rule_code);

CREATE OR REPLACE FUNCTION notify_ai_report_policy_rules_changed()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_notify('ai_report_policy_rules_changed', '');
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_ai_report_policy_rules_notify
AFTER INSERT OR UPDATE OR DELETE ON ai_report_policy_rules
FOR EACH STATEMENT EXECUTE FUNCTION notify_ai_report_policy_rules_changed();

CREATE TRIGGER trg_ai_question_tasks_updated
BEFORE UPDATE ON ai_question_tasks
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_knowledge_sources_updated
BEFORE UPDATE ON knowledge_sources
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ai_report_policy_rules_updated
BEFORE UPDATE ON ai_report_policy_rules
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ai_question_tasks VALIDATE CONSTRAINT ck_ai_question_tasks_geo_scope;
ALTER TABLE ai_question_tasks VALIDATE CONSTRAINT ck_ai_question_tasks_geo_confidence;
ALTER TABLE ai_question_tasks VALIDATE CONSTRAINT ck_ai_question_tasks_region_context;
ALTER TABLE ai_question_tasks VALIDATE CONSTRAINT ck_ai_question_tasks_answer_outcome;
ALTER TABLE ai_question_tasks VALIDATE CONSTRAINT ck_ai_question_tasks_processing_lease;
ALTER TABLE ai_question_tasks VALIDATE CONSTRAINT ck_ai_question_tasks_completed;
ALTER TABLE knowledge_sources VALIDATE CONSTRAINT ck_knowledge_sources_status;
ALTER TABLE knowledge_sources VALIDATE CONSTRAINT ck_knowledge_sources_geo_scope;
ALTER TABLE knowledge_sources VALIDATE CONSTRAINT ck_knowledge_sources_region_context;
ALTER TABLE knowledge_sources VALIDATE CONSTRAINT ck_knowledge_sources_metadata;
ALTER TABLE knowledge_sources VALIDATE CONSTRAINT ck_knowledge_sources_ingestion_lease;

COMMIT;
