# Chat Notice Memory

## 2026-07-21

- Implemented chat notice registration/list/pin/unpin for all room types.
- Frontend contract uses `ChatNoticeResponse` fields in this order: `noticeId`, `roomId`, `message`, `createdByUserId`, `createdAt`, `pinned`.
- The pinned representative notice is stored on `chat_rooms.pinned_notice_id` and returned as `pinnedNotice` from the list endpoint.
- Stale unpin is intentionally implemented with conditional SQL update:
  `UPDATE chat_rooms SET pinned_notice_id = NULL WHERE room_id = ? AND pinned_notice_id = ?`.
- Source eligibility is text-only visible user messages. The backend never accepts notice content metadata from the client.
- Notion API entries:
  - Register: `39e71cfb-4244-8152-a7db-e7284cfcb2f6`
  - List: `39e71cfb-4244-8193-8d96-c8889e510bd5`
  - Pin: `3a471cfb-4244-8154-bef3-ddb31d391336`
  - Unpin: `3a471cfb-4244-8111-8d97-d235edebd598`
