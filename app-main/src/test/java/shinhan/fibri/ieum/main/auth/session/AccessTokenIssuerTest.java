package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;

class AccessTokenIssuerTest {

	@Test
	void issueCreatesHs256JwtWithSessionClaims() {
		String secret = "01234567890123456789012345678901";
		AccessTokenIssuer issuer = new AccessTokenIssuer(secret, 30);

		String token = issuer.issue(42L, "session-id", "user@example.com", UserRole.user);

		var jwt = NimbusJwtDecoder
			.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
			.macAlgorithm(MacAlgorithm.HS256)
			.build()
			.decode(token);

		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString("sid")).isEqualTo("session-id");
		assertThat(jwt.getClaimAsString("email")).isEqualTo("user@example.com");
		assertThat(jwt.getClaimAsString("role")).isEqualTo("user");
		assertThat(jwt.getIssuedAt()).isNotNull();
		assertThat(jwt.getExpiresAt()).isNotNull();
		assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
	}

	@Test
	void constructorRejectsNonPositiveTtlMinutes() {
		assertThatThrownBy(() -> new AccessTokenIssuer("01234567890123456789012345678901", 0))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("app.jwt.access-token-ttl-minutes must be positive");
	}
}
