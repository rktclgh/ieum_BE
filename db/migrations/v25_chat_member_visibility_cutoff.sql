BEGIN;

ALTER TABLE chat_members
    ADD COLUMN visible_after_message_id BIGINT NOT NULL DEFAULT 0;

ALTER TABLE chat_members
    ADD CONSTRAINT ck_chat_members_visible_after_message_id
    CHECK (visible_after_message_id >= 0);

UPDATE chat_members AS member
SET visible_after_message_id = COALESCE((
        SELECT MAX(message.message_id)
        FROM messages AS message
        WHERE message.room_id = member.room_id
    ), 0)
FROM chat_rooms AS room
WHERE room.room_id = member.room_id
  AND room.room_type IN ('direct', 'question')
  AND member.left_at IS NOT NULL;

COMMIT;
