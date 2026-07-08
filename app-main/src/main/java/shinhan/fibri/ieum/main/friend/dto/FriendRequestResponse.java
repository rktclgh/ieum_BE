package shinhan.fibri.ieum.main.friend.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.main.support.ProfileImageUrls;

public record FriendRequestResponse(
	Long userId,
	String nickname,
	String profileImageUrl,
	OffsetDateTime requestedAt
) {

	public static FriendRequestResponse from(User user, OffsetDateTime requestedAt) {
		return new FriendRequestResponse(
			user.getId(),
			user.getNickname(),
			ProfileImageUrls.of(user),
			requestedAt
		);
	}
}
