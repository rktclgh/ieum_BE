package shinhan.fibri.ieum.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.main.auth.service.GoogleSocialIdentityVerifier;
import shinhan.fibri.ieum.main.auth.service.KakaoSocialIdentityVerifier;
import shinhan.fibri.ieum.main.auth.service.KakaoTokenClient;
import shinhan.fibri.ieum.main.auth.service.RestClientKakaoTokenClient;

@Configuration
public class SocialAuthConfig {

	@Bean
	RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}

	@Bean
	JwtDecoder googleSocialJwtDecoder(
		@Value("${app.auth.social.google.jwk-set-uri}") String jwkSetUri
	) {
		return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
	}

	@Bean
	GoogleSocialIdentityVerifier googleSocialIdentityVerifier(
		@Qualifier("googleSocialJwtDecoder") JwtDecoder googleSocialJwtDecoder,
		@Value("${app.auth.social.google.client-id}") String clientId
	) {
		return new GoogleSocialIdentityVerifier(googleSocialJwtDecoder, clientId);
	}

	@Bean
	JwtDecoder kakaoSocialJwtDecoder(
		@Value("${app.auth.social.kakao.jwk-set-uri}") String jwkSetUri
	) {
		return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
	}

	@Bean
	KakaoTokenClient kakaoTokenClient(
		RestClient.Builder restClientBuilder,
		@Value("${app.auth.social.kakao.token-uri}") String tokenUri,
		@Value("${app.auth.social.kakao.rest-api-key}") String restApiKey,
		@Value("${app.auth.social.kakao.client-secret}") String clientSecret
	) {
		return new RestClientKakaoTokenClient(restClientBuilder.build(), tokenUri, restApiKey, clientSecret);
	}

	@Bean
	KakaoSocialIdentityVerifier kakaoSocialIdentityVerifier(
		KakaoTokenClient kakaoTokenClient,
		@Qualifier("kakaoSocialJwtDecoder") JwtDecoder kakaoSocialJwtDecoder,
		@Value("${app.auth.social.kakao.rest-api-key}") String restApiKey,
		@Value("${app.auth.social.kakao.redirect-uris}") String redirectUris
	) {
		return new KakaoSocialIdentityVerifier(
			kakaoTokenClient,
			kakaoSocialJwtDecoder,
			restApiKey,
			commaSeparatedSet(redirectUris)
		);
	}

	private Set<String> commaSeparatedSet(String value) {
		return Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(item -> !item.isBlank())
			.collect(Collectors.toUnmodifiableSet());
	}
}
