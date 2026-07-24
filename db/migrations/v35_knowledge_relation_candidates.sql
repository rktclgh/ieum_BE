BEGIN;

CREATE TABLE IF NOT EXISTS public.knowledge_relation_extraction_tasks (
    task_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES public.knowledge_sources(source_id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempts SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    last_error_message TEXT,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_relation_extraction_tasks_source UNIQUE (source_id),
    CONSTRAINT ck_knowledge_relation_extraction_tasks_status
        CHECK (status IN ('pending','processing','retry','completed','dead','invalidated')),
    CONSTRAINT ck_knowledge_relation_extraction_tasks_lease
        CHECK ((status = 'processing') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_knowledge_relation_extraction_tasks_attempts
        CHECK (attempts >= 0),
    CONSTRAINT ck_knowledge_relation_extraction_tasks_completed
        CHECK (status <> 'completed' OR completed_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_relation_extraction_tasks_claim
    ON public.knowledge_relation_extraction_tasks(status, next_attempt_at, created_at, task_id)
    WHERE status IN ('pending','retry');
CREATE INDEX IF NOT EXISTS idx_knowledge_relation_extraction_tasks_expired_lease
    ON public.knowledge_relation_extraction_tasks(lease_until, task_id)
    WHERE status = 'processing';

CREATE TABLE IF NOT EXISTS public.knowledge_relation_candidates (
    candidate_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES public.knowledge_sources(source_id) ON DELETE CASCADE,
    evidence_chunk_id BIGINT NOT NULL,
    candidate_fingerprint CHAR(64) NOT NULL,
    subject_text VARCHAR(200) NOT NULL,
    predicate VARCHAR(32) NOT NULL,
    object_text VARCHAR(200) NOT NULL,
    confidence NUMERIC(5,4) NOT NULL,
    evidence_excerpt TEXT NOT NULL,
    extraction_provider VARCHAR(80) NOT NULL,
    extraction_model VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    version INTEGER NOT NULL DEFAULT 1,
    reviewer_user_id BIGINT REFERENCES public.users(user_id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    review_note TEXT,
    promotion_relation_id BIGINT REFERENCES public.knowledge_relations(relation_id) ON DELETE SET NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_relation_candidates_source_fingerprint
        UNIQUE (source_id, candidate_fingerprint),
    CONSTRAINT fk_knowledge_relation_candidates_same_source_evidence
        FOREIGN KEY (source_id, evidence_chunk_id)
        REFERENCES public.knowledge_chunks(source_id, chunk_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_relation_candidates_fingerprint
        CHECK (candidate_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_relation_candidates_predicate
        CHECK (predicate IN (
            'requires','applies_to','located_in','exception_of','prevents',
            'supports','has_deadline','depends_on','reported_to','used_for'
        )),
    CONSTRAINT ck_knowledge_relation_candidates_status
        CHECK (status IN ('pending','approved','rejected','invalidated')),
    CONSTRAINT ck_knowledge_relation_candidates_terms
        CHECK (btrim(subject_text) <> '' AND btrim(object_text) <> ''),
    CONSTRAINT ck_knowledge_relation_candidates_confidence
        CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_knowledge_relation_candidates_evidence
        CHECK (
            btrim(evidence_excerpt) <> ''
            AND char_length(evidence_excerpt) BETWEEN 1 AND 200
        ),
    CONSTRAINT ck_knowledge_relation_candidates_version
        CHECK (version >= 1)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_relation_candidates_review
    ON public.knowledge_relation_candidates(status, created_at, candidate_id)
    WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_knowledge_relation_candidates_source
    ON public.knowledge_relation_candidates(source_id, candidate_id);

ALTER TABLE public.knowledge_relation_candidates
    DROP CONSTRAINT IF EXISTS ck_knowledge_relation_candidates_status;
ALTER TABLE public.knowledge_relation_candidates
    ADD CONSTRAINT ck_knowledge_relation_candidates_status
        CHECK (status IN ('pending','approved','rejected','invalidated'));

ALTER TABLE public.knowledge_relation_candidates
    DROP CONSTRAINT IF EXISTS ck_knowledge_relation_candidates_evidence;
ALTER TABLE public.knowledge_relation_candidates
    ADD CONSTRAINT ck_knowledge_relation_candidates_evidence
        CHECK (
            btrim(evidence_excerpt) <> ''
            AND char_length(evidence_excerpt) BETWEEN 1 AND 200
        );

DO $$
BEGIN
    IF to_regclass('public.admin_audit_logs') IS NOT NULL
       AND EXISTS (
            SELECT 1
            FROM pg_constraint constraint_row
            WHERE constraint_row.conrelid = 'public.admin_audit_logs'::regclass
              AND constraint_row.conname = 'ck_admin_audit_logs_action'
              AND regexp_replace(
                  pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
                  '[[:space:]()]',
                  '',
                  'g'
              ) = 'action=ANYARRAY[''USER_SANCTION_CREATED''::text,''USER_ACTIVATED''::text,''USER_ROLE_CHANGED''::text,''REPORT_CONFIRMED''::text,''REPORT_DISMISSED''::text,''INQUIRY_ANSWERED''::text]'
        )
       AND EXISTS (
            SELECT 1
            FROM pg_constraint constraint_row
            WHERE constraint_row.conrelid = 'public.admin_audit_logs'::regclass
              AND constraint_row.conname = 'ck_admin_audit_logs_target_type'
              AND regexp_replace(
                  pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
                  '[[:space:]()]',
                  '',
                  'g'
              ) = 'target_type=ANYARRAY[''user''::text,''report''::text,''inquiry''::text]'
        ) THEN
        ALTER TABLE public.admin_audit_logs
            DROP CONSTRAINT IF EXISTS ck_admin_audit_logs_action;
        ALTER TABLE public.admin_audit_logs
            ADD CONSTRAINT ck_admin_audit_logs_action CHECK (
                action IN (
                    'USER_SANCTION_CREATED',
                    'USER_ACTIVATED',
                    'USER_ROLE_CHANGED',
                    'REPORT_CONFIRMED',
                    'REPORT_DISMISSED',
                    'INQUIRY_ANSWERED',
                    'KNOWLEDGE_RELATION_APPROVED',
                    'KNOWLEDGE_RELATION_REJECTED'
                )
            );

        ALTER TABLE public.admin_audit_logs
            DROP CONSTRAINT IF EXISTS ck_admin_audit_logs_target_type;
        ALTER TABLE public.admin_audit_logs
            ADD CONSTRAINT ck_admin_audit_logs_target_type CHECK (
                target_type IN ('user', 'report', 'inquiry', 'knowledge_relation_candidate')
            );
    END IF;
END
$$;

COMMIT;
