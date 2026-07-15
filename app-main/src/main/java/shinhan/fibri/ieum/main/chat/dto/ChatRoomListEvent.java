package shinhan.fibri.ieum.main.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRoomListEvent(
	String type,
	ChatRoomSummaryResponse room,
	Long roomId
) {

	public static ChatRoomListEvent upsert(ChatRoomSummaryResponse room) {
		return new ChatRoomListEvent("upsert", room, null);
	}

	public static ChatRoomListEvent remove(Long roomId) {
		return new ChatRoomListEvent("remove", null, roomId);
	}
}
