package shinhan.fibri.ieum.main.user.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.User;

public record UserSearchResponse(
	Long userId,
	String nickname,
	String nationality,
	String profileImageUrl,
	boolean isFriend,
	OffsetDateTime lastActiveAt
) {

	public static UserSearchResponse from(User user, boolean isFriend) {
		return new UserSearchResponse(
			user.getId(),
			user.getNickname(),
			user.getNationality(),
			profileImageUrl(user),
			isFriend,
			user.getLastActiveAt()
		);
	}

	private static String profileImageUrl(User user) {
		if (user.getProfileFileId() == null) {
			return null;
		}
		return "/api/v1/files/" + user.getProfileFileId();
	}
}
