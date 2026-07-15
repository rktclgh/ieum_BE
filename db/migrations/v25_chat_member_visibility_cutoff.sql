ALTER TABLE chat_members
    ADD COLUMN visible_after_message_id BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_chat_members_visible_after_message_id
        CHECK (visible_after_message_id >= 0);

CREATE INDEX idx_messages_room_message_id
    ON messages(room_id, message_id DESC);
