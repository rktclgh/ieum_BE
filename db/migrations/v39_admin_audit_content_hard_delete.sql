BEGIN;

ALTER TABLE public.admin_audit_logs
    DROP CONSTRAINT IF EXISTS ck_admin_audit_logs_action,
    ADD CONSTRAINT ck_admin_audit_logs_action CHECK (
        action IN (
            'USER_SANCTION_CREATED',
            'USER_ACTIVATED',
            'USER_ROLE_CHANGED',
            'REPORT_CONFIRMED',
            'REPORT_DISMISSED',
            'INQUIRY_ANSWERED',
            'KNOWLEDGE_RELATION_APPROVED',
            'KNOWLEDGE_RELATION_REJECTED',
            'QUESTION_HARD_DELETED',
            'MEETING_HARD_DELETED'
        )
    ),
    DROP CONSTRAINT IF EXISTS ck_admin_audit_logs_target_type,
    ADD CONSTRAINT ck_admin_audit_logs_target_type CHECK (
        target_type IN (
            'user',
            'report',
            'inquiry',
            'knowledge_relation_candidate',
            'question',
            'meeting'
        )
    );

DO $verify$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = 'public.admin_audit_logs'::regclass
          AND constraint_row.conname = 'ck_admin_audit_logs_action'
          AND regexp_replace(
              pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
              '[[:space:]()]',
              '',
              'g'
          ) = 'action=ANYARRAY[''USER_SANCTION_CREATED''::text,''USER_ACTIVATED''::text,''USER_ROLE_CHANGED''::text,''REPORT_CONFIRMED''::text,''REPORT_DISMISSED''::text,''INQUIRY_ANSWERED''::text,''KNOWLEDGE_RELATION_APPROVED''::text,''KNOWLEDGE_RELATION_REJECTED''::text,''QUESTION_HARD_DELETED''::text,''MEETING_HARD_DELETED''::text]'
    ) THEN
        RAISE EXCEPTION 'admin_audit_logs.action content hard-delete verification failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = 'public.admin_audit_logs'::regclass
          AND constraint_row.conname = 'ck_admin_audit_logs_target_type'
          AND regexp_replace(
              pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
              '[[:space:]()]',
              '',
              'g'
          ) = 'target_type=ANYARRAY[''user''::text,''report''::text,''inquiry''::text,''knowledge_relation_candidate''::text,''question''::text,''meeting''::text]'
    ) THEN
        RAISE EXCEPTION 'admin_audit_logs.target_type content hard-delete verification failed';
    END IF;
END
$verify$;

COMMIT;
