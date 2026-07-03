package shinhan.fibri.ieum.main.auth.service;

import shinhan.fibri.ieum.main.auth.dto.RefreshResponse;

public record RefreshResult(
	RefreshResponse response,
	String accessToken,
	String refreshToken,
	String csrfToken
) {
}
