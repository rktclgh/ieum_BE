package shinhan.fibri.ieum.main.auth.service;

import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialTokenException;

public class GoogleSocialIdentityVerifier {

	private static final Set<String> ALLOWED_ISSUERS = Set.of(
		"https://accounts.google.com",
		"accounts.google.com"
	);

	private final JwtDecoder jwtDecoder;
	private final String clientId;

	public GoogleSocialIdentityVerifier(JwtDecoder jwtDecoder, String clientId) {
		this.jwtDecoder = jwtDecoder;
		this.clientId = clientId;
	}

	public VerifiedSocialIdentity verify(String idToken, String nonce) {
		Jwt jwt = decode(idToken);
		String issuer = jwt.getClaimAsString("iss");
		if (issuer == null || !ALLOWED_ISSUERS.contains(issuer)) {
			throw new InvalidSocialTokenException();
		}
		List<String> audience = jwt.getAudience();
		if (audience == null || !audience.contains(clientId)) {
			throw new InvalidSocialTokenException();
		}
		if (nonce != null && !nonce.equals(jwt.getClaimAsString("nonce"))) {
			throw new InvalidSocialTokenException();
		}
		String subject = jwt.getSubject();
		String email = jwt.getClaimAsString("email");
		if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
			throw new InvalidSocialTokenException();
		}
		return new VerifiedSocialIdentity(
			AuthProvider.google,
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
