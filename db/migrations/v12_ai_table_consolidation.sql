-- v12: app-ai 11-table draft -> 4-table compact schema
-- Scope lock: this migration touches only the listed AI/RAG/KG tables and one unused enum.
-- Safety: all legacy AI tables must be empty; otherwise abort before any DROP.

BEGIN;

DO $$
DECLARE
    table_name TEXT;
    row_count BIGINT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'question_ai_jobs',
        'question_embeddings',
        'ai_answer_metadata',
        'ai_answer_sources',
        'knowledge_sources',
        'knowledge_chunks',
        'knowledge_chunk_embeddings',
        'kg_entities',
        'kg_entity_aliases',
        'kg_relations',
        'ai_inference_runs'
    ]
    LOOP
        EXECUTE format('SELECT count(*) FROM %I', table_name) INTO row_count;
        IF row_count <> 0 THEN
            RAISE EXCEPTION 'v12 requires empty AI tables: % has % rows', table_name, row_count;
        END IF;
    END LOOP;
END
$$;

DROP TABLE ai_answer_sources;
DROP TABLE ai_answer_metadata;
DROP TABLE question_embeddings;
DROP TABLE question_ai_jobs;
DROP TABLE kg_relations;
DROP TABLE kg_entity_aliases;
DROP TABLE kg_entities;
DROP TABLE knowledge_chunk_embeddings;
DROP TABLE knowledge_chunks;
DROP TABLE knowledge_sources;
DROP TABLE ai_inference_runs;
DROP TYPE ai_evidence_source_type;

CREATE TABLE ai_question_tasks (
    question_id BIGINT PRIMARY KEY REFERENCES questions(question_id) ON DELETE CASCADE,
    status ai_job_status NOT NULL DEFAULT 'pending',
    stage ai_job_stage NOT NULL DEFAULT 'discovered',
    attempts SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    locked_by VARCHAR(100),
    embedding vector(768),
    embedding_model VARCHAR(100),
    answer_id BIGINT UNIQUE REFERENCES answers(answer_id) ON DELETE SET NULL,
    generation_model VARCHAR(120),
    prompt_version VARCHAR(80),
    grounding_status VARCHAR(30) CHECK (grounding_status IN ('grounded', 'insufficient_evidence')),
    grounding_score NUMERIC(5,4) CHECK (grounding_score BETWEEN 0 AND 1),
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_error_code VARCHAR(100),
    last_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CHECK ((status = 'processing') = (lease_until IS NOT NULL AND locked_by IS NOT NULL)),
    CHECK ((embedding IS NULL) = (embedding_model IS NULL)),
    CHECK (jsonb_typeof(evidence) = 'array' AND jsonb_array_length(evidence) <= 8),
    CHECK (status <> 'completed' OR (
        completed_at IS NOT NULL
        AND embedding IS NOT NULL
        AND answer_id IS NOT NULL
        AND generation_model IS NOT NULL
        AND grounding_status IS NOT NULL
    )),
    CHECK (status <> 'cancelled' OR cancelled_at IS NOT NULL)
);
CREATE INDEX idx_ai_question_tasks_claim
    ON ai_question_tasks(status, next_attempt_at, created_at)
    WHERE status IN ('pending', 'retry');
CREATE INDEX idx_ai_question_tasks_expired_lease
    ON ai_question_tasks(lease_until)
    WHERE status = 'processing';
CREATE INDEX idx_ai_question_tasks_embedding_hnsw
    ON ai_question_tasks USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

CREATE TABLE knowledge_sources (
    source_id BIGSERIAL PRIMARY KEY,
    source_type knowledge_source_type NOT NULL,
    question_id BIGINT REFERENCES questions(question_id) ON DELETE CASCADE,
    answer_id BIGINT REFERENCES answers(answer_id) ON DELETE CASCADE,
    external_ref VARCHAR(500),
    content_hash CHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deactivated_at TIMESTAMPTZ,
    CHECK (source_type <> 'accepted_human_answer'
        OR (question_id IS NOT NULL AND answer_id IS NOT NULL)),
    CHECK (active OR deactivated_at IS NOT NULL)
);
CREATE UNIQUE INDEX uidx_knowledge_source_answer
    ON knowledge_sources(answer_id) WHERE answer_id IS NOT NULL;
CREATE INDEX idx_knowledge_sources_active
    ON knowledge_sources(source_type, source_id) WHERE active;

CREATE TABLE knowledge_chunks (
    chunk_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES knowledge_sources(source_id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    chunk_order SMALLINT NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768) NOT NULL,
    embedding_model VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id, chunk_order),
    CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX idx_knowledge_chunks_embedding_hnsw
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE knowledge_relations (
    relation_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES knowledge_sources(source_id) ON DELETE CASCADE,
    subject VARCHAR(200) NOT NULL CHECK (btrim(subject) <> ''),
    predicate VARCHAR(120) NOT NULL CHECK (btrim(predicate) <> ''),
    object VARCHAR(200) NOT NULL CHECK (btrim(object) <> ''),
    confidence NUMERIC(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id, subject, predicate, object),
    CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX idx_knowledge_relations_subject
    ON knowledge_relations(subject, predicate);
CREATE INDEX idx_knowledge_relations_object
    ON knowledge_relations(object, predicate);

COMMIT;
