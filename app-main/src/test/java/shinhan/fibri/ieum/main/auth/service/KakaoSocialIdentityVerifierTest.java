package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialTokenException;

class KakaoSocialIdentityVerifierTest {

	@Test
	void verifyExchangesCodeAndReturnsKakaoIdentityFromValidIdToken() {
		KakaoTokenClient tokenClient = mock(KakaoTokenClient.class);
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		KakaoSocialIdentityVerifier verifier = verifier(tokenClient, jwtDecoder);
		when(tokenClient.exchangeCode("authorization-code", "http://localhost:3000/oauth/kakao/callback"))
			.thenReturn("id-token");
		when(jwtDecoder.decode("id-token")).thenReturn(kakaoJwt(builder -> builder
			.subject("kakao-sub-123")
			.claim("email", "social@example.com")
			.claim("email_verified", true)
		));

		VerifiedSocialIdentity identity = verifier.verify(
			"authorization-code",
			"http://localhost:3000/oauth/kakao/callback"
		);

		assertThat(identity).isEqualTo(new VerifiedSocialIdentity(
			AuthProvider.kakao,
			"kakao-sub-123",
			"social@example.com",
			true
		));
	}

	@Test
	void verifyRejectsRedirectUriOutsideAllowList() {
		KakaoSocialIdentityVerifier verifier = verifier(mock(KakaoTokenClient.class), mock(JwtDecoder.class));

		assertThatThrownBy(() -> verifier.verify("authorization-code", "https://evil.example.com/callback"))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsNullRedirectUri() {
		KakaoSocialIdentityVerifier verifier = verifier(mock(KakaoTokenClient.class), mock(JwtDecoder.class));

		assertThatThrownBy(() -> verifier.verify("authorization-code", null))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsTokenExchangeFailure() {
		KakaoTokenClient tokenClient = mock(KakaoTokenClient.class);
		KakaoSocialIdentityVerifier verifier = verifier(tokenClient, mock(JwtDecoder.class));
		when(tokenClient.exchangeCode("bad-code", "http://localhost:3000/oauth/kakao/callback"))
			.thenThrow(new InvalidSocialTokenException());

		assertThatThrownBy(() -> verifier.verify("bad-code", "http://localhost:3000/oauth/kakao/callback"))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsJwtDecodeFailure() {
		KakaoTokenClient tokenClient = mock(KakaoTokenClient.class);
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		KakaoSocialIdentityVerifier verifier = verifier(tokenClient, jwtDecoder);
		when(tokenClient.exchangeCode("authorization-code", "http://localhost:3000/oauth/kakao/callback"))
			.thenReturn("id-token");
		when(jwtDecoder.decode("id-token")).thenThrow(new JwtException("bad token"));

		assertThatThrownBy(() -> verifier.verify("authorization-code", "http://localhost:3000/oauth/kakao/callback"))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsWrongAudience() {
		KakaoTokenClient tokenClient = mock(KakaoTokenClient.class);
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		KakaoSocialIdentityVerifier verifier = verifier(tokenClient, jwtDecoder);
		when(tokenClient.exchangeCode("authorization-code", "http://localhost:3000/oauth/kakao/callback"))
			.thenReturn("id-token");
		when(jwtDecoder.decode("id-token")).thenReturn(kakaoJwt(builder -> builder
			.audience(List.of("other-client-id"))
			.subject("kakao-sub-123")
			.claim("email", "social@example.com")
		));

		assertThatThrownBy(() -> verifier.verify("authorization-code", "http://localhost:3000/oauth/kakao/callback"))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	@Test
	void verifyRejectsMissingAudience() {
		KakaoTokenClient tokenClient = mock(KakaoTokenClient.class);
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		KakaoSocialIdentityVerifier verifier = verifier(tokenClient, jwtDecoder);
		when(tokenClient.exchangeCode("authorization-code", "http://localhost:3000/oauth/kakao/callback"))
			.thenReturn("id-token");
		when(jwtDecoder.decode("id-token")).thenReturn(kakaoJwt(builder -> builder
			.claims(claims -> claims.remove("aud"))
			.subject("kakao-sub-123")
			.claim("email", "social@example.com")
		));

		assertThatThrownBy(() -> verifier.verify("authorization-code", "http://localhost:3000/oauth/kakao/callback"))
			.isInstanceOf(InvalidSocialTokenException.class);
	}

	private KakaoSocialIdentityVerifier verifier(KakaoTokenClient tokenClient, JwtDecoder jwtDecoder) {
		return new KakaoSocialIdentityVerifier(
			tokenClient,
			jwtDecoder,
			"kakao-rest-api-key",
			Set.of("http://localhost:3000/oauth/kakao/callback")
		);
	}

	private Jwt kakaoJwt(java.util.function.Consumer<Jwt.Builder> customizer) {
		Jwt.Builder builder = Jwt.withTokenValue("id-token")
			.header("alg", "RS256")
			.claim("iss", "https://kauth.kakao.com")
			.audience(List.of("kakao-rest-api-key"))
			.issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
			.expiresAt(Instant.parse("2026-01-01T00:30:00Z"));
		customizer.accept(builder);
		return builder.build();
	}
}
