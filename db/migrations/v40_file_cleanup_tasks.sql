BEGIN;

CREATE TABLE IF NOT EXISTS public.file_cleanup_tasks (
    task_id BIGSERIAL PRIMARY KEY,
    s3_key TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    attempts SMALLINT NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    locked_by TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(80),
    last_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_file_cleanup_tasks_status
        CHECK (status IN ('pending', 'processing', 'retry', 'completed', 'dead')),
    CONSTRAINT ck_file_cleanup_tasks_attempts_nonnegative
        CHECK (attempts >= 0 AND attempts <= 20),
    CONSTRAINT ck_file_cleanup_tasks_completed_at_status
        CHECK (
            (status <> 'completed' AND completed_at IS NULL)
            OR (status = 'completed' AND completed_at IS NOT NULL)
        )
);

CREATE INDEX IF NOT EXISTS idx_file_cleanup_tasks_claim
    ON public.file_cleanup_tasks (status, next_attempt_at, created_at, task_id)
    WHERE status IN ('pending', 'retry');

CREATE INDEX IF NOT EXISTS idx_file_cleanup_tasks_expired_lease
    ON public.file_cleanup_tasks (lease_until)
    WHERE status = 'processing';

DO $$
BEGIN
    IF to_regclass('public.file_cleanup_tasks') IS NULL THEN
        RAISE EXCEPTION 'file_cleanup_tasks table was not created';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'public.file_cleanup_tasks'::regclass
           AND conname = 'ck_file_cleanup_tasks_status'
    ) THEN
        RAISE EXCEPTION 'file_cleanup_tasks status constraint is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'public.file_cleanup_tasks'::regclass
           AND conname = 'ck_file_cleanup_tasks_attempts_nonnegative'
    ) THEN
        RAISE EXCEPTION 'file_cleanup_tasks attempts constraint is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'public.file_cleanup_tasks'::regclass
           AND conname = 'ck_file_cleanup_tasks_completed_at_status'
    ) THEN
        RAISE EXCEPTION 'file_cleanup_tasks completed-at status constraint is missing';
    END IF;
END
$$;

COMMIT;
