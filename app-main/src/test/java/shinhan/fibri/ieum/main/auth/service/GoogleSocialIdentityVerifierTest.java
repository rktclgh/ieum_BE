package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialTokenException;

class GoogleSocialIdentityVerifierTest {

	@Test
	void verifyReturnsGoogleIdentityFromValidIdToken() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		when(jwtDecoder.decode("id-token")).thenReturn(googleJwt(builder -> builder
			.subject("google-sub-123")
			.claim("email", "social@example.com")
			.claim("email_verified", true)
			.claim("nonce", "nonce-1")
		));
		GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(jwtDecoder, "google-client-id");

		VerifiedSocialIdentity identity = verifier.verify("id-token", "nonce-1");

		assertThat(identity).isEqualTo(new VerifiedSocialIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		));
	}

	@Test
	void verifyRejectsTokenDecodeFailure() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("bad token"));
		GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(jwtDecoder, "google-client-id");

		assertThatThrownBy(() -> verifier.verify("bad-token", null))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsWrongIssuer() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		when(jwtDecoder.decode("id-token")).thenReturn(googleJwt(builder -> builder
			.claim("iss", "https://evil.example.com")
			.subject("google-sub-123")
			.claim("email", "social@example.com")
			.claim("email_verified", true)
		));
		GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(jwtDecoder, "google-client-id");

		assertThatThrownBy(() -> verifier.verify("id-token", null))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsMissingIssuer() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		when(jwtDecoder.decode("id-token")).thenReturn(googleJwt(builder -> builder
			.claims(claims -> claims.remove("iss"))
			.subject("google-sub-123")
			.claim("email", "social@example.com")
			.claim("email_verified", true)
		));
		GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(jwtDecoder, "google-client-id");

		assertThatThrownBy(() -> verifier.verify("id-token", null))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsWrongAudience() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		when(jwtDecoder.decode("id-token")).thenReturn(googleJwt(builder -> builder
			.audience(List.of("other-client-id"))
			.subject("google-sub-123")
			.claim("email", "social@example.com")
			.claim("email_verified", true)
		));
		GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(jwtDecoder, "google-client-id");

		assertThatThrownBy(() -> verifier.verify("id-token", null))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsMissingAudience() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		when(jwtDecoder.decode("id-token")).thenReturn(googleJwt(builder -> builder
			.claims(claims -> claims.remove("aud"))
			.subject("google-sub-123")
			.claim("email", "social@example.com")
			.claim("email_verified", true)
		));
		GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(jwtDecoder, "google-client-id");

		assertThatThrownBy(() -> verifier.verify("id-token", null))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsNonceMismatchWhenNonceIsProvided() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		when(jwtDecoder.decode("id-token")).thenReturn(googleJwt(builder -> builder
			.subject("google-sub-123")
			.claim("email", "social@example.com")
			.claim("email_verified", true)
			.claim("nonce", "other-nonce")
		));
		GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(jwtDecoder, "google-client-id");

		assertThatThrownBy(() -> verifier.verify("id-token", "nonce-1"))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	private Jwt googleJwt(java.util.function.Consumer<Jwt.Builder> customizer) {
		Jwt.Builder builder = Jwt.withTokenValue("id-token")
			.header("alg", "RS256")
			.claim("iss", "https://accounts.google.com")
			.audience(List.of("google-client-id"))
			.issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
			.expiresAt(Instant.parse("2026-01-01T00:30:00Z"));
		customizer.accept(builder);
		return builder.build();
	}
}
