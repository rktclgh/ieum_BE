package shinhan.fibri.ieum.main.auth.session;

public record IssuedAuthSession(
	String accessToken,
	String refreshToken,
	String csrfToken
) {
}
