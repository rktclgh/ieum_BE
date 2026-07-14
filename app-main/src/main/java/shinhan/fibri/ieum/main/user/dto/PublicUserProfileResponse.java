package shinhan.fibri.ieum.main.user.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.main.support.ProfileImageUrls;

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
			ProfileImageUrls.of(user),
			user.getGrade().name(),
			isFriend,
			user.getLastActiveAt()
		);
	}
}
