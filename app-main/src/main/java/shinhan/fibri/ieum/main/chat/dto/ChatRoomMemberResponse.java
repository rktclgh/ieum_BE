package shinhan.fibri.ieum.main.chat.dto;

import java.util.UUID;
import shinhan.fibri.ieum.common.auth.domain.User;

public record ChatRoomMemberResponse(
	Long userId,
	String nickname,
	String profileImageUrl
) {

	public static ChatRoomMemberResponse from(User user) {
		return new ChatRoomMemberResponse(
			user.getId(),
			user.getNickname(),
			profileImageUrl(user.getProfileFileId())
		);
	}

	private static String profileImageUrl(UUID profileFileId) {
		return profileFileId == null ? null : "/api/v1/files/%s".formatted(profileFileId);
	}
}
