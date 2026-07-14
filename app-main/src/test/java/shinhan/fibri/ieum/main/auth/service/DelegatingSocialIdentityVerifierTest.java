package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.main.auth.dto.SocialAuthRequest;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialTokenException;

class DelegatingSocialIdentityVerifierTest {

	@Test
	void verifyDelegatesGoogleRequestToGoogleVerifier() {
		GoogleSocialIdentityVerifier googleVerifier = mock(GoogleSocialIdentityVerifier.class);
		KakaoSocialIdentityVerifier kakaoVerifier = mock(KakaoSocialIdentityVerifier.class);
		DelegatingSocialIdentityVerifier verifier = new DelegatingSocialIdentityVerifier(googleVerifier, kakaoVerifier);
		SocialAuthRequest request = new SocialAuthRequest("google", "id-token", null, null, "nonce-1");
		VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		);
		when(googleVerifier.verify("id-token", "nonce-1")).thenReturn(identity);

		assertThat(verifier.verify(request)).isEqualTo(identity);
		verify(googleVerifier).verify("id-token", "nonce-1");
	}

	@Test
	void verifyDelegatesKakaoRequestToKakaoVerifier() {
		GoogleSocialIdentityVerifier googleVerifier = mock(GoogleSocialIdentityVerifier.class);
		KakaoSocialIdentityVerifier kakaoVerifier = mock(KakaoSocialIdentityVerifier.class);
		DelegatingSocialIdentityVerifier verifier = new DelegatingSocialIdentityVerifier(googleVerifier, kakaoVerifier);
		SocialAuthRequest request = new SocialAuthRequest(
			"kakao",
			null,
			"authorization-code",
			"http://localhost:3000/oauth/kakao/callback",
			null
		);
		VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
			AuthProvider.kakao,
			"kakao-sub-123",
			"social@example.com",
			true
		);
		when(kakaoVerifier.verify("authorization-code", "http://localhost:3000/oauth/kakao/callback"))
			.thenReturn(identity);

		assertThat(verifier.verify(request)).isEqualTo(identity);
		verify(kakaoVerifier).verify("authorization-code", "http://localhost:3000/oauth/kakao/callback");
	}

	@Test
	void verifyRejectsProviderWithoutVerifier() {
		GoogleSocialIdentityVerifier googleVerifier = mock(GoogleSocialIdentityVerifier.class);
		KakaoSocialIdentityVerifier kakaoVerifier = mock(KakaoSocialIdentityVerifier.class);
		DelegatingSocialIdentityVerifier verifier = new DelegatingSocialIdentityVerifier(googleVerifier, kakaoVerifier);

		assertThatThrownBy(() -> verifier.verify(new SocialAuthRequest("apple", "id-token", null, null, null)))
			.isInstanceOf(InvalidSocialTokenException.class);
	}
}
