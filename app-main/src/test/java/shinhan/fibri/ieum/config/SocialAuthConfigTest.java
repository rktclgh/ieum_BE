package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import shinhan.fibri.ieum.main.auth.service.KakaoSocialIdentityVerifier;
import shinhan.fibri.ieum.main.auth.service.KakaoTokenClient;

class SocialAuthConfigTest {

	@Test
	void kakaoVerifierAllowsNullRedirectUriConfiguration() {
		SocialAuthConfig config = new SocialAuthConfig();

		KakaoSocialIdentityVerifier verifier = config.kakaoSocialIdentityVerifier(
			mock(KakaoTokenClient.class),
			mock(JwtDecoder.class),
			"kakao-rest-api-key",
			null
		);

		assertThat(verifier).isNotNull();
	}
}
