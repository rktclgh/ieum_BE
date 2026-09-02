BEGIN;

CREATE TABLE IF NOT EXISTS public.ai_job_outbox (
    outbox_id          BIGSERIAL PRIMARY KEY,
    job_id             UUID NOT NULL,
    job_type           TEXT NOT NULL,
    job_key            BIGINT NOT NULL,
    routing_key        TEXT NOT NULL,
    schema_version     SMALLINT NOT NULL DEFAULT 1,
    payload            JSONB NOT NULL,
    status             TEXT NOT NULL DEFAULT 'pending',
    attempts           SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token        UUID,
    lease_until        TIMESTAMPTZ,
    locked_by          TEXT,
    last_error_code    VARCHAR(80),
    last_error_message TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at       TIMESTAMPTZ,
    CONSTRAINT uq_ai_job_outbox_job_id UNIQUE (job_id),
    CONSTRAINT ck_ai_job_outbox_job_type
        CHECK (job_type IN ('question_answer_dispatch', 'accepted_answer_knowledge_ingest')),
    CONSTRAINT ck_ai_job_outbox_status
        CHECK (status IN ('pending', 'publishing', 'retry', 'published', 'dead')),
    CONSTRAINT ck_ai_job_outbox_attempts
        CHECK (attempts >= 0 AND attempts <= 20),
    CONSTRAINT ck_ai_job_outbox_job_key_positive
        CHECK (job_key > 0),
    CONSTRAINT ck_ai_job_outbox_schema_version
        CHECK (schema_version >= 1),
    CONSTRAINT ck_ai_job_outbox_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_ai_job_outbox_routing_key
        CHECK (btrim(routing_key) <> ''),
    CONSTRAINT ck_ai_job_outbox_publishing_lease
        CHECK ((status = 'publishing')
               = (lease_until IS NOT NULL AND locked_by IS NOT NULL AND lease_token IS NOT NULL)),
    CONSTRAINT ck_ai_job_outbox_published_at_status
        CHECK ((status <> 'published' AND published_at IS NULL)
               OR (status = 'published' AND published_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_ai_job_outbox_claim
    ON public.ai_job_outbox (next_attempt_at, outbox_id)
    WHERE status IN ('pending', 'retry');

CREATE INDEX IF NOT EXISTS idx_ai_job_outbox_expired_lease
    ON public.ai_job_outbox (lease_until)
    WHERE status = 'publishing';

CREATE INDEX IF NOT EXISTS idx_ai_job_outbox_retention
    ON public.ai_job_outbox (published_at)
    WHERE status = 'published';

DO $$
BEGIN
    IF to_regclass('public.ai_job_outbox') IS NULL THEN
        RAISE EXCEPTION 'ai_job_outbox table was not created';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'public.ai_job_outbox'::regclass
           AND conname = 'uq_ai_job_outbox_job_id'
    ) THEN
        RAISE EXCEPTION 'ai_job_outbox job_id uniqueness constraint is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_indexes
         WHERE schemaname = 'public'
           AND tablename = 'ai_job_outbox'
           AND indexname = 'idx_ai_job_outbox_claim'
    ) THEN
        RAISE EXCEPTION 'ai_job_outbox claim index is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_indexes
         WHERE schemaname = 'public'
           AND tablename = 'ai_job_outbox'
           AND indexname = 'idx_ai_job_outbox_expired_lease'
    ) THEN
        RAISE EXCEPTION 'ai_job_outbox expired-lease index is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_indexes
         WHERE schemaname = 'public'
           AND tablename = 'ai_job_outbox'
           AND indexname = 'idx_ai_job_outbox_retention'
    ) THEN
        RAISE EXCEPTION 'ai_job_outbox retention index is missing';
    END IF;
END
$$;

COMMIT;
