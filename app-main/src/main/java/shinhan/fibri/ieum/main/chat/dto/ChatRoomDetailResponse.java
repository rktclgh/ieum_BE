package shinhan.fibri.ieum.main.chat.dto;

import java.util.List;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.RoomType;

public record ChatRoomDetailResponse(
	Long roomId,
	RoomType roomType,
	Long meetingId,
	Long questionId,
	boolean pinned,
	boolean notifyEnabled,
	List<ChatRoomMemberResponse> members
) {

	public static ChatRoomDetailResponse from(ChatRoom room, ChatMember currentMember, List<ChatMember> members) {
		return new ChatRoomDetailResponse(
			room.getId(),
			room.getRoomType(),
			room.getMeetingId(),
			room.getQuestionId(),
			currentMember.getPinnedAt() != null,
			currentMember.isNotifyEnabled(),
			members.stream()
				.filter(ChatMember::isActive)
				.map(member -> ChatRoomMemberResponse.from(member.getUser()))
				.toList()
		);
	}
}
