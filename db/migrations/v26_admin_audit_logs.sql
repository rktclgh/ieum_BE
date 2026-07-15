BEGIN;

CREATE TABLE admin_audit_logs (
    audit_id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id BIGINT NOT NULL,
    details JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_audit_logs_action CHECK (
        action IN (
            'USER_SANCTION_CREATED',
            'USER_ACTIVATED',
            'USER_ROLE_CHANGED',
            'REPORT_CONFIRMED',
            'REPORT_DISMISSED',
            'INQUIRY_ANSWERED'
        )
    ),
    CONSTRAINT ck_admin_audit_logs_target_type CHECK (
        target_type IN ('user', 'report', 'inquiry')
    ),
    CONSTRAINT ck_admin_audit_logs_details_object CHECK (
        jsonb_typeof(details) = 'object'
    )
);

CREATE INDEX idx_admin_audit_logs_actor_created
    ON admin_audit_logs(actor_user_id, created_at DESC, audit_id DESC);
CREATE INDEX idx_admin_audit_logs_target_created
    ON admin_audit_logs(target_type, target_id, created_at DESC, audit_id DESC);
CREATE INDEX idx_admin_audit_logs_created_desc
    ON admin_audit_logs(created_at DESC, audit_id DESC);

COMMIT;
