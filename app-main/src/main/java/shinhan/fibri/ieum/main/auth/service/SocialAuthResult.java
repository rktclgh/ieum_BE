package shinhan.fibri.ieum.main.auth.service;

import shinhan.fibri.ieum.main.auth.dto.SocialAuthResponse;

public record SocialAuthResult(
	SocialAuthResponse response,
	String accessToken,
	String refreshToken,
	String csrfToken
) {
}
