BEGIN;

ALTER TABLE public.messages
  ADD COLUMN message_type VARCHAR(16) NOT NULL DEFAULT 'user';

ALTER TABLE public.messages
  ADD CONSTRAINT ck_messages_message_type
  CHECK (message_type IN ('user', 'system'));

ALTER TABLE public.messages
  ADD CONSTRAINT ck_messages_system_text_only
  CHECK (
    message_type <> 'system'
    OR (content IS NOT NULL AND image_file_id IS NULL)
  );

COMMIT;
