package shinhan.fibri.ieum.main.admin.service;

import shinhan.fibri.ieum.main.admin.dto.AdminLoginResponse;

public record AdminLoginResult(
	AdminLoginResponse response,
	String accessToken,
	String refreshToken,
	String csrfToken
) {
}
