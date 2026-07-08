package shinhan.fibri.ieum.main.user.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.main.support.ProfileImageUrls;

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
			ProfileImageUrls.of(user),
			isFriend,
			user.getLastActiveAt()
		);
	}
}
