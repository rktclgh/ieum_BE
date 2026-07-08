package shinhan.fibri.ieum.main.user.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.User;

public record PublicUserProfileResponse(
	Long userId,
	String nickname,
	String nationality,
	String profileImageUrl,
	String grade,
	boolean isFriend,
	OffsetDateTime lastActiveAt
) {

	public static PublicUserProfileResponse from(User user, boolean isFriend) {
		return new PublicUserProfileResponse(
			user.getId(),
			user.getNickname(),
			user.getNationality(),
			profileImageUrl(user),
			user.getGrade().name(),
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
