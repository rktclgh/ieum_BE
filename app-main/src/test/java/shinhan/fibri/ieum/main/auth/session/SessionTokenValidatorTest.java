package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

class SessionTokenValidatorTest {

	@Test
	void validateReturnsAuthenticatedUserWhenJwtAndRedisSessionAreValid() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		SessionTokenValidator validator = new SessionTokenValidator(jwtDecoder, sessionStore);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		)));

		Optional<AuthenticatedUser> result = validator.validate("access-token");

		assertThat(result).hasValue(new AuthenticatedUser(
			42L,
			"user@example.com",
			UserRole.user,
			UserStatus.active
		));
	}

	@Test
	void validateSessionReturnsAuthenticatedUserWithSessionIdWhenJwtAndRedisSessionAreValid() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		SessionTokenValidator validator = new SessionTokenValidator(jwtDecoder, sessionStore);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		)));

		Optional<ValidatedAuthSession> result = validator.validateSession("access-token");

		assertThat(result).hasValue(new ValidatedAuthSession(
			new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active),
			"sid-1"
		));
	}

	@Test
	void validateReturnsEmptyWhenRedisSessionDoesNotExist() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		SessionTokenValidator validator = new SessionTokenValidator(jwtDecoder, sessionStore);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.empty());

		Optional<AuthenticatedUser> result = validator.validate("access-token");

		assertThat(result).isEmpty();
	}

	@Test
	void validateReturnsEmptyWhenJwtIsInvalid() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		SessionTokenValidator validator = new SessionTokenValidator(jwtDecoder, sessionStore);
		when(jwtDecoder.decode("invalid-token")).thenThrow(new JwtException("bad token"));

		Optional<AuthenticatedUser> result = validator.validate("invalid-token");

		assertThat(result).isEmpty();
	}

	@Test
	void validateReturnsEmptyWhenSessionIsSuspended() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		SessionTokenValidator validator = new SessionTokenValidator(jwtDecoder, sessionStore);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.suspended,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		)));

		Optional<AuthenticatedUser> result = validator.validate("access-token");

		assertThat(result).isEmpty();
	}

	@Test
	void validateReturnsEmptyWhenJwtEmailDoesNotMatchRedisSession() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		SessionTokenValidator validator = new SessionTokenValidator(jwtDecoder, sessionStore);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "attacker@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		)));

		Optional<AuthenticatedUser> result = validator.validate("access-token");

		assertThat(result).isEmpty();
	}

	private Jwt jwt(String subject, String sessionId, String email, String role) {
		return new Jwt(
			"access-token",
			Instant.parse("2026-07-03T00:00:00Z"),
			Instant.parse("2026-07-03T00:30:00Z"),
			Map.of("alg", "HS256"),
			Map.of(
				"sub", subject,
				"sid", sessionId,
				"email", email,
				"role", role
			)
		);
	}
}
