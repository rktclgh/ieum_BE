package shinhan.fibri.ieum.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.main.auth.session.AccessTokenIssuer;
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
	AccessTokenIssuer accessTokenIssuer(
		@Value("${app.jwt.secret}") String secret,
		@Value("${app.jwt.access-token-ttl-minutes}") int ttlMinutes
	) {
		return new AccessTokenIssuer(secret, ttlMinutes);
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
