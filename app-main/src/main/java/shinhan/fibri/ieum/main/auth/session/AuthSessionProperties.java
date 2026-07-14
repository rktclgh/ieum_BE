package shinhan.fibri.ieum.main.auth.session;

public record AuthSessionProperties(
	boolean secureCookie,
	String sameSite,
	String domain,
	long accessTokenMaxAgeSeconds,
	long refreshTokenMaxAgeSeconds
) {
}
