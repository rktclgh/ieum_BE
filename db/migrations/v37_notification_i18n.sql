BEGIN;

-- Keep legacy title/body rows renderable while new clients receive a stable
-- message key and the parameter snapshot needed for localized rendering.
-- IF EXISTS/IF NOT EXISTS makes this safe for environments where the original
-- v36 file was applied manually before this deployment path existed.
ALTER TABLE IF EXISTS public.notifications
    ADD COLUMN IF NOT EXISTS message_key VARCHAR(100),
    ADD COLUMN IF NOT EXISTS message_params JSONB;

DO $verify$
BEGIN
    IF to_regclass('public.notifications') IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_attribute
            WHERE attrelid = 'public.notifications'::regclass
              AND attname = 'message_key'
              AND atttypid = 'character varying'::regtype
              AND atttypmod = 104
              AND NOT attnotnull
              AND NOT atthasdef
              AND attnum > 0
              AND NOT attisdropped
        ) THEN
            RAISE EXCEPTION 'notifications.message_key schema verification failed';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM pg_attribute
            WHERE attrelid = 'public.notifications'::regclass
              AND attname = 'message_params'
              AND atttypid = 'jsonb'::regtype
              AND atttypmod = -1
              AND NOT attnotnull
              AND NOT atthasdef
              AND attnum > 0
              AND NOT attisdropped
        ) THEN
            RAISE EXCEPTION 'notifications.message_params schema verification failed';
        END IF;
    END IF;
END
$verify$;

COMMIT;
