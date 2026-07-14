package shinhan.fibri.ieum.main.friend.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.main.support.ProfileImageUrls;

public record BlockedUserResponse(
	Long userId,
	String nickname,
	String profileImageUrl,
	OffsetDateTime blockedAt
) {

	public static BlockedUserResponse from(User user, OffsetDateTime blockedAt) {
		return new BlockedUserResponse(
			user.getId(),
			user.getNickname(),
			ProfileImageUrls.of(user),
			blockedAt
		);
	}
}
