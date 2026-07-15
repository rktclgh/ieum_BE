package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;

class SessionTokenValidatorTest {

	@Test
	void validateReturnsAuthenticatedUserWhenJwtRedisAndCanonicalStateAreValid() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		SessionTokenValidator validator = validator(jwtDecoder, sessionStore, userRepository);
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			7L
		);
		UserAuthState canonical = canonical();
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(session));
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.of(canonical));

		Optional<AuthenticatedUser> result = validator.validate("access-token");

		assertThat(result).hasValue(new AuthenticatedUser(
			42L,
			canonical.email(),
			canonical.role(),
			canonical.status()
		));
	}

	@Test
	void validateSessionReturnsCanonicalPrincipalWithRedisSessionId() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		SessionTokenValidator validator = validator(jwtDecoder, sessionStore, userRepository);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(session()));
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.of(canonical()));

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
		UserRepository userRepository = mock(UserRepository.class);
		SessionTokenValidator validator = validator(jwtDecoder, sessionStore, userRepository);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.empty());

		assertThat(validator.validate("access-token")).isEmpty();
		verifyNoInteractions(userRepository);
	}

	@Test
	void validateReturnsEmptyWhenJwtIsInvalid() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		SessionTokenValidator validator = validator(jwtDecoder, sessionStore, userRepository);
		when(jwtDecoder.decode("invalid-token")).thenThrow(new JwtException("bad token"));

		assertThat(validator.validate("invalid-token")).isEmpty();
		verifyNoInteractions(sessionStore, userRepository);
	}

	@Test
	void validateReturnsEmptyWhenCanonicalUserDoesNotExist() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		SessionTokenValidator validator = validator(jwtDecoder, sessionStore, userRepository);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(session()));
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.empty());

		assertThat(validator.validate("access-token")).isEmpty();
	}

	@ParameterizedTest(name = "canonical mismatch rejects access: {0}")
	@MethodSource("mismatchingCanonicalStates")
	void validateReturnsEmptyWhenCanonicalStateDoesNotMatch(String ignored, UserAuthState canonical) {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		SessionTokenValidator validator = validator(jwtDecoder, sessionStore, userRepository);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(session()));
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.of(canonical));

		assertThat(validator.validate("access-token")).isEmpty();
	}

	@Test
	void validateReturnsEmptyBeforeDatabaseLookupWhenJwtIdentityDoesNotMatchRedis() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		SessionTokenValidator validator = validator(jwtDecoder, sessionStore, userRepository);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "attacker@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(session()));

		assertThat(validator.validate("access-token")).isEmpty();
		verifyNoInteractions(userRepository);
	}

	@Test
	void validatePropagatesCanonicalDatabaseRuntimeFailure() {
		JwtDecoder jwtDecoder = mock(JwtDecoder.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		SessionTokenValidator validator = validator(jwtDecoder, sessionStore, userRepository);
		when(jwtDecoder.decode("access-token")).thenReturn(jwt("42", "sid-1", "user@example.com", "user"));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(session()));
		when(userRepository.findAuthStateById(42L)).thenThrow(new IllegalStateException("database unavailable"));

		assertThatThrownBy(() -> validator.validate("access-token"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("database unavailable");
	}

	private SessionTokenValidator validator(
		JwtDecoder jwtDecoder,
		RedisAuthSessionStore sessionStore,
		UserRepository userRepository
	) {
		return new SessionTokenValidator(
			jwtDecoder,
			sessionStore,
			new CanonicalAuthStateVerifier(userRepository)
		);
	}

	private static Stream<Arguments> mismatchingCanonicalStates() {
		return Stream.of(
			Arguments.of("changed email", new UserAuthState("changed@example.com", UserRole.user, UserStatus.active, 7L)),
			Arguments.of("changed role", new UserAuthState("user@example.com", UserRole.admin, UserStatus.active, 7L)),
			Arguments.of("suspended", new UserAuthState("user@example.com", UserRole.user, UserStatus.suspended, 7L)),
			Arguments.of("changed version", new UserAuthState("user@example.com", UserRole.user, UserStatus.active, 8L))
		);
	}

	private AuthSession session() {
		return new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			7L
		);
	}

	private UserAuthState canonical() {
		return new UserAuthState("user@example.com", UserRole.user, UserStatus.active, 7L);
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
