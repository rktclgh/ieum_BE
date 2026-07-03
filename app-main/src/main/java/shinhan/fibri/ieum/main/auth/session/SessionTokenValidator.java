package shinhan.fibri.ieum.main.auth.session;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

@Component
@RequiredArgsConstructor
public class SessionTokenValidator {

	private final JwtDecoder jwtDecoder;
	private final RedisAuthSessionStore sessionStore;

	public Optional<AuthenticatedUser> validate(String accessToken) {
		Jwt jwt;
		try {
			jwt = jwtDecoder.decode(accessToken);
		} catch (JwtException exception) {
			return Optional.empty();
		}
		String sessionId = jwt.getClaimAsString("sid");
		return sessionStore.findBySessionId(sessionId)
			.filter(session -> session.status() == UserStatus.active)
			.filter(session -> String.valueOf(session.userId()).equals(jwt.getSubject()))
			.filter(session -> session.email().equals(jwt.getClaimAsString("email")))
			.filter(session -> session.role().name().equals(jwt.getClaimAsString("role")))
			.map(session -> new AuthenticatedUser(
				session.userId(),
				session.email(),
				session.role(),
				session.status()
			));
	}
}
