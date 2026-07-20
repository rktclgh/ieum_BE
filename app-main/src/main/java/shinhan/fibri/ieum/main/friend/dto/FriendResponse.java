package shinhan.fibri.ieum.main.friend.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.main.support.ProfileImageUrls;

public record FriendResponse(
	Long userId,
	String nickname,
	String profileImageUrl,
	String nationality,
	OffsetDateTime lastActiveAt,
	boolean active
) {

	public static FriendResponse from(User user, boolean active) {
		return new FriendResponse(
			user.getId(),
			user.getNickname(),
			ProfileImageUrls.of(user),
			user.getNationality(),
			user.getLastActiveAt(),
			active
		);
	}

}
