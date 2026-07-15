package shinhan.fibri.ieum.main.chat.dto;

import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.RoomType;

public record ChatRoomResponse(
	Long roomId,
	RoomType roomType,
	Long meetingId,
	Long questionId,
	String questionTitle
) {

	public static ChatRoomResponse from(ChatRoom room, String questionTitle) {
		return new ChatRoomResponse(
			room.getId(),
			room.getRoomType(),
			room.getMeetingId(),
			room.getQuestionId(),
			questionTitle
		);
	}
}
