-- v32: add an optional one-level reply parent for chat messages.
-- The parent may be physically removed later; preserve the reply body and clear only this link.
BEGIN;

ALTER TABLE public.messages
  ADD COLUMN reply_to_message_id BIGINT;

ALTER TABLE public.messages
  ADD CONSTRAINT fk_messages_reply_to_message
  FOREIGN KEY (reply_to_message_id)
  REFERENCES public.messages(message_id)
  ON DELETE SET NULL;

COMMIT;
