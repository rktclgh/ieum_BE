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
            'USER_PROMOTED_TO_ADMIN',
            'QUESTION_UPDATED',
            'MEETING_UPDATED',
            'QUESTION_HARD_DELETED',
            'MEETING_HARD_DELETED'
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
          ) = 'action=ANYARRAY[''USER_SANCTION_CREATED''::text,''USER_ACTIVATED''::text,''USER_ROLE_CHANGED''::text,''REPORT_CONFIRMED''::text,''REPORT_DISMISSED''::text,''INQUIRY_ANSWERED''::text,''KNOWLEDGE_RELATION_APPROVED''::text,''KNOWLEDGE_RELATION_REJECTED''::text,''USER_PROMOTED_TO_ADMIN''::text,''QUESTION_UPDATED''::text,''MEETING_UPDATED''::text,''QUESTION_HARD_DELETED''::text,''MEETING_HARD_DELETED''::text]'
    ) THEN
        RAISE EXCEPTION 'admin_audit_logs.action content management verification failed';
    END IF;
END
$verify$;

COMMIT;
