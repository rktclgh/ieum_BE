package shinhan.fibri.ieum.main.auth.session;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;

public class AccessTokenIssuer {

	private final NimbusJwtEncoder encoder;
	private final int ttlMinutes;

	public AccessTokenIssuer(String secret, int ttlMinutes) {
		String validatedSecret = AuthSecretValidator.requireAtLeast32Bytes(secret, "app.jwt.secret");
		SecretKeySpec secretKey = new SecretKeySpec(validatedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
		this.ttlMinutes = ttlMinutes;
	}

	public String issue(Long userId, String sessionId, String email, UserRole role) {
		Instant issuedAt = Instant.now();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(String.valueOf(userId))
			.issuedAt(issuedAt)
			.expiresAt(issuedAt.plusSeconds(ttlMinutes * 60L))
			.claim("sid", sessionId)
			.claim("email", email)
			.claim("role", role.name())
			.build();

		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
