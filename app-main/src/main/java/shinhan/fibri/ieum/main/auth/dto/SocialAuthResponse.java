package shinhan.fibri.ieum.main.auth.dto;

import shinhan.fibri.ieum.common.auth.domain.UserRole;

public record SocialAuthResponse(
	boolean isNewUser,
	Long userId,
	UserRole role,
	String socialSignupToken,
	Integer expiresInSeconds
) {

	public static SocialAuthResponse existingUser(Long userId, UserRole role) {
		return new SocialAuthResponse(false, userId, role, null, null);
	}

	public static SocialAuthResponse newUser(String socialSignupToken, int expiresInSeconds) {
		return new SocialAuthResponse(true, null, null, socialSignupToken, expiresInSeconds);
	}
}
