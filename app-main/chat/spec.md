# Chat Notice API Spec

## Scope

- Applies to all chat room types: `direct`, `group`, `question`.
- Only active room members can register, list, pin, or unpin notices.
- A notice is created from an existing visible user text message. The client sends only `messageId`; message content, sender, and timestamps are derived by the server.
- Image-only, system, deleted, foreign-room, or hidden-before-rejoin messages are not eligible notice sources.

## Endpoints

### Register Notice

`POST /api/v1/chat/rooms/{roomId}/notices`

Request:

```json
{
  "messageId": 501
}
```

Response:

- `201 Created` when the `(roomId, messageId)` notice row is first inserted.
- `200 OK` when the notice already exists; the canonical existing notice is returned.
- `403 NOT_ROOM_MEMBER` when the user is not an active member.
- `404 ROOM_NOT_FOUND` when the room does not exist.
- `404 MESSAGE_NOT_FOUND` when the source message is unavailable or ineligible.

### List Notices

`GET /api/v1/chat/rooms/{roomId}/notices?cursor={cursor}&size=20`

Response:

```json
{
  "items": [
    {
      "noticeId": 901,
      "roomId": 100,
      "message": {
        "messageId": 501,
        "roomId": 100,
        "senderId": 77,
        "senderNickname": "sender",
        "senderProfileImageUrl": null,
        "messageType": "user",
        "content": "notice text",
        "imageUrl": null,
        "createdAt": "2026-07-21T10:00:00+09:00",
        "replyTo": null
      },
      "createdByUserId": 42,
      "createdAt": "2026-07-21T11:00:00+09:00",
      "pinned": false
    }
  ],
  "nextCursor": null,
  "pinnedNotice": null
}
```

`pinnedNotice` is returned separately so the frontend can render the chat-room banner even when the pinned notice is outside the current notice page.

### Pin Notice

`PUT /api/v1/chat/rooms/{roomId}/notices/{noticeId}/pin`

- Sets or replaces the single representative notice for the room.
- The notice must belong to the same room and be visible to the requester.
- Response is the pinned `ChatNoticeResponse`.

### Unpin Notice

`DELETE /api/v1/chat/rooms/{roomId}/notices/{noticeId}/pin`

- Clears `chat_rooms.pinned_notice_id` only when the current pinned notice still matches `noticeId`.
- Stale unpin requests are no-op `204 No Content` and cannot clear a newer replacement.

## Storage

- `chat_notices.notice_id` primary key.
- `chat_notices(room_id, message_id)` unique index makes registration idempotent.
- `chat_rooms.pinned_notice_id` references `chat_notices(notice_id)` with `ON DELETE SET NULL`.

## Notion API IDs

- Register: `39e71cfb-4244-8152-a7db-e7284cfcb2f6`
- List: `39e71cfb-4244-8193-8d96-c8889e510bd5`
- Pin: `3a471cfb-4244-8154-bef3-ddb31d391336`
- Unpin: `3a471cfb-4244-8111-8d97-d235edebd598`
