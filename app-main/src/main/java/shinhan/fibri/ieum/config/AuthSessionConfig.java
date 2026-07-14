package shinhan.fibri.ieum.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import shinhan.fibri.ieum.main.auth.session.AccessTokenIssuer;
import shinhan.fibri.ieum.main.auth.session.AuthSecretValidator;
import shinhan.fibri.ieum.main.auth.session.AuthCookieWriter;
import shinhan.fibri.ieum.main.auth.session.AuthSessionProperties;
import shinhan.fibri.ieum.main.auth.session.OpaqueTokenGenerator;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;

@Configuration
public class AuthSessionConfig {

	@Bean
	OpaqueTokenGenerator opaqueTokenGenerator() {
		return new OpaqueTokenGenerator();
	}

	@Bean
	Sha256TokenHasher sha256TokenHasher() {
		return new Sha256TokenHasher();
	}

	@Bean
	SecretKeySpec jwtSecretKey(@Value("${app.jwt.secret}") String secret) {
		String validatedSecret = AuthSecretValidator.requireAtLeast32Bytes(secret, "app.jwt.secret");
		return new SecretKeySpec(validatedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	AccessTokenIssuer accessTokenIssuer(
		SecretKeySpec jwtSecretKey,
		@Value("${app.jwt.access-token-ttl-minutes}") int ttlMinutes
	) {
		return new AccessTokenIssuer(jwtSecretKey, ttlMinutes);
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKeySpec jwtSecretKey) {
		return NimbusJwtDecoder
			.withSecretKey(jwtSecretKey)
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
	}

	@Bean
	AuthSessionProperties authSessionProperties(
		@Value("${app.cookie.secure}") boolean secureCookie,
		@Value("${app.cookie.same-site}") String sameSite,
		@Value("${app.cookie.domain}") String domain,
		@Value("${app.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes,
		@Value("${app.jwt.refresh-token-ttl-days}") long refreshTokenTtlDays
	) {
		return new AuthSessionProperties(
			secureCookie,
			sameSite,
			domain,
			accessTokenTtlMinutes * 60,
			refreshTokenTtlDays * 24 * 60 * 60
		);
	}

	@Bean
	AuthCookieWriter authCookieWriter(AuthSessionProperties properties) {
		return new AuthCookieWriter(properties);
	}
}
