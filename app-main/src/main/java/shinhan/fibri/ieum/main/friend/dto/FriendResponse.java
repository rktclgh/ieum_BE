package shinhan.fibri.ieum.main.friend.dto;

import java.time.Clock;
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

	private static final long ACTIVE_WINDOW_MINUTES = 5;

	public static FriendResponse from(User user, Clock clock) {
		return from(user, OffsetDateTime.now(clock));
	}

	public static FriendResponse from(User user, OffsetDateTime now) {
		return new FriendResponse(
			user.getId(),
			user.getNickname(),
			ProfileImageUrls.of(user),
			user.getNationality(),
			user.getLastActiveAt(),
			isActive(user.getLastActiveAt(), now)
		);
	}

	private static boolean isActive(OffsetDateTime lastActiveAt, OffsetDateTime now) {
		return lastActiveAt != null && !lastActiveAt.isBefore(now.minusMinutes(ACTIVE_WINDOW_MINUTES));
	}

}
