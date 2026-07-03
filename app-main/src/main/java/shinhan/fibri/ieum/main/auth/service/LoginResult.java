package shinhan.fibri.ieum.main.auth.service;

import shinhan.fibri.ieum.main.auth.dto.LoginResponse;

public record LoginResult(
	LoginResponse response,
	String accessToken,
	String refreshToken,
	String csrfToken
) {
}
