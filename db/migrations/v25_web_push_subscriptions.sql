BEGIN;

CREATE TABLE IF NOT EXISTS web_push_subscriptions (
    subscription_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    session_id VARCHAR(64) NOT NULL,
    endpoint TEXT NOT NULL,
    endpoint_hash CHAR(64) NOT NULL UNIQUE,
    p256dh VARCHAR(512) NOT NULL,
    auth_secret VARCHAR(256) NOT NULL,
    binding_version BIGINT NOT NULL DEFAULT 1 CHECK (binding_version > 0),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_web_push_subscriptions_user
    ON web_push_subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_web_push_subscriptions_session
    ON web_push_subscriptions(session_id);

DO $migration$
BEGIN
    IF to_regprocedure('public.set_updated_at()') IS NULL THEN
        EXECUTE $function$
            CREATE FUNCTION public.set_updated_at()
            RETURNS TRIGGER AS $body$
            BEGIN
                NEW.updated_at = now();
                RETURN NEW;
            END;
            $body$ LANGUAGE plpgsql
        $function$;
    END IF;
END
$migration$;

DROP TRIGGER IF EXISTS trg_web_push_subscriptions_updated ON web_push_subscriptions;
CREATE TRIGGER trg_web_push_subscriptions_updated
    BEFORE UPDATE ON web_push_subscriptions
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

COMMIT;
