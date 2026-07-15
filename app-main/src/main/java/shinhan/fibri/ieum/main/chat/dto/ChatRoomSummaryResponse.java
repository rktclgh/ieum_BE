package shinhan.fibri.ieum.main.chat.dto;

import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;

public record ChatRoomSummaryResponse(
	Long roomId,
	RoomType roomType,
	Long meetingId,
	Long questionId,
	String questionTitle,
	boolean pinned,
	boolean notifyEnabled,
	long unreadCount,
	ChatMessageResponse lastMessage
) {

	public static ChatRoomSummaryResponse from(
		ChatRoom room,
		ChatMember member,
		long unreadCount,
		Message lastMessage,
		String questionTitle
	) {
		return new ChatRoomSummaryResponse(
			room.getId(),
			room.getRoomType(),
			room.getMeetingId(),
			room.getQuestionId(),
			questionTitle,
			member.getPinnedAt() != null,
			member.isNotifyEnabled(),
			unreadCount,
			lastMessage == null ? null : ChatMessageResponse.from(lastMessage)
		);
	}
}
