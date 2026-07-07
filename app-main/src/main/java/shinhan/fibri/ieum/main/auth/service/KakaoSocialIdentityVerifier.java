package shinhan.fibri.ieum.main.auth.service;

import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialTokenException;

public class KakaoSocialIdentityVerifier {

	private static final String ISSUER = "https://kauth.kakao.com";

	private final KakaoTokenClient tokenClient;
	private final JwtDecoder jwtDecoder;
	private final String restApiKey;
	private final Set<String> allowedRedirectUris;

	public KakaoSocialIdentityVerifier(
		KakaoTokenClient tokenClient,
		JwtDecoder jwtDecoder,
		String restApiKey,
		Set<String> allowedRedirectUris
	) {
		this.tokenClient = tokenClient;
		this.jwtDecoder = jwtDecoder;
		this.restApiKey = restApiKey;
		this.allowedRedirectUris = allowedRedirectUris;
	}

	public VerifiedSocialIdentity verify(String code, String redirectUri) {
		if (!allowedRedirectUris.contains(redirectUri)) {
			throw new InvalidSocialTokenException();
		}
		Jwt jwt = decode(tokenClient.exchangeCode(code, redirectUri));
		if (!ISSUER.equals(jwt.getClaimAsString("iss"))) {
			throw new InvalidSocialTokenException();
		}
		if (!jwt.getAudience().contains(restApiKey)) {
			throw new InvalidSocialTokenException();
		}
		String subject = jwt.getSubject();
		String email = jwt.getClaimAsString("email");
		if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
			throw new InvalidSocialTokenException();
		}
		return new VerifiedSocialIdentity(
			AuthProvider.kakao,
			subject,
			email,
			Boolean.TRUE.equals(jwt.getClaim("email_verified"))
		);
	}

	private Jwt decode(String idToken) {
		try {
			return jwtDecoder.decode(idToken);
		} catch (JwtException exception) {
			throw new InvalidSocialTokenException();
		}
	}
}
