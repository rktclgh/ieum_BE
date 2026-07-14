package shinhan.fibri.ieum.main.auth.service;

import shinhan.fibri.ieum.main.auth.dto.SocialSignupResponse;

public record SocialSignupResult(
	SocialSignupResponse response,
	String accessToken,
	String refreshToken,
	String csrfToken
) {
}
