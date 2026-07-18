package shinhan.fibri.ieum.main.chat.dto;

import java.util.UUID;

public record ChatRoomCounterpartResponse(
	Long userId,
	String nickname,
	String profileImageUrl,
	String nationality,
	boolean active
) {

	public static ChatRoomCounterpartResponse of(
		Long userId,
		String nickname,
		UUID profileFileId,
		String nationality,
		boolean active
	) {
		return new ChatRoomCounterpartResponse(
			userId,
			nickname,
			profileFileId == null ? null : "/api/v1/files/%s".formatted(profileFileId),
			nationality,
			active
		);
	}
}
