BEGIN;

CREATE TABLE IF NOT EXISTS public.chat_notices (
    notice_id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_chat_notices_room_message
    ON public.chat_notices(room_id, message_id);
CREATE INDEX IF NOT EXISTS idx_chat_notices_room_created
    ON public.chat_notices(room_id, created_at DESC, notice_id DESC);

ALTER TABLE IF EXISTS public.chat_rooms
    ADD COLUMN IF NOT EXISTS pinned_notice_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.chat_notices'::regclass
          AND conname = 'fk_chat_notices_room'
    ) THEN
        ALTER TABLE public.chat_notices
            ADD CONSTRAINT fk_chat_notices_room
            FOREIGN KEY (room_id) REFERENCES public.chat_rooms(room_id)
            ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.chat_notices'::regclass
          AND conname = 'fk_chat_notices_message'
    ) THEN
        ALTER TABLE public.chat_notices
            ADD CONSTRAINT fk_chat_notices_message
            FOREIGN KEY (message_id) REFERENCES public.messages(message_id)
            ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.chat_notices'::regclass
          AND conname = 'fk_chat_notices_created_by'
    ) THEN
        ALTER TABLE public.chat_notices
            ADD CONSTRAINT fk_chat_notices_created_by
            FOREIGN KEY (created_by) REFERENCES public.users(user_id)
            ON DELETE SET NULL;
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.chat_rooms'::regclass
          AND conname = 'fk_chat_rooms_pinned_notice'
    ) THEN
        ALTER TABLE public.chat_rooms
            ADD CONSTRAINT fk_chat_rooms_pinned_notice
            FOREIGN KEY (pinned_notice_id) REFERENCES public.chat_notices(notice_id)
            ON DELETE SET NULL;
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_attribute column_row
        WHERE column_row.attrelid = 'public.chat_rooms'::regclass
          AND column_row.attname = 'pinned_notice_id'
          AND column_row.atttypid = 'bigint'::regtype
          AND NOT column_row.attnotnull
          AND column_row.attnum > 0
          AND NOT column_row.attisdropped
    ) THEN
        RAISE EXCEPTION 'chat_rooms.pinned_notice_id schema verification failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.chat_rooms'::regclass
          AND conname = 'fk_chat_rooms_pinned_notice'
          AND confrelid = 'public.chat_notices'::regclass
          AND confdeltype = 'n'
    ) THEN
        RAISE EXCEPTION 'chat_rooms.pinned_notice_id foreign-key verification failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_index index_row
        JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
        WHERE index_row.indrelid = 'public.chat_notices'::regclass
          AND index_class.relname = 'uidx_chat_notices_room_message'
          AND index_row.indisunique
    ) THEN
        RAISE EXCEPTION 'chat_notices unique index verification failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.chat_notices'::regclass
          AND conname = 'fk_chat_notices_room'
          AND confrelid = 'public.chat_rooms'::regclass
          AND confdeltype = 'c'
    ) THEN
        RAISE EXCEPTION 'chat_notices.room_id foreign-key verification failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.chat_notices'::regclass
          AND conname = 'fk_chat_notices_message'
          AND confrelid = 'public.messages'::regclass
          AND confdeltype = 'c'
    ) THEN
        RAISE EXCEPTION 'chat_notices.message_id foreign-key verification failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.chat_notices'::regclass
          AND conname = 'fk_chat_notices_created_by'
          AND confrelid = 'public.users'::regclass
          AND confdeltype = 'n'
    ) THEN
        RAISE EXCEPTION 'chat_notices.created_by foreign-key verification failed';
    END IF;
END
$$;

COMMIT;
