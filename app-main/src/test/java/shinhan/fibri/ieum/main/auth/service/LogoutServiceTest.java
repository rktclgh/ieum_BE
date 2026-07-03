package shinhan.fibri.ieum.main.auth.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;

class LogoutServiceTest {

	@Test
	void logoutRevokesSessionFoundByRefreshToken() {
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
		LogoutService service = new LogoutService(sessionStore, tokenHasher);
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		);
		when(tokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
		when(sessionStore.findByRefreshTokenHash("refresh-hash")).thenReturn(Optional.of(session));

		service.logout("refresh-token");

		verify(sessionStore).revokeSession("sid-1");
	}

	@Test
	void logoutIsIdempotentWhenRefreshTokenDoesNotMatchSession() {
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
		LogoutService service = new LogoutService(sessionStore, tokenHasher);
		when(tokenHasher.hash("missing-refresh-token")).thenReturn("missing-hash");
		when(sessionStore.findByRefreshTokenHash("missing-hash")).thenReturn(Optional.empty());

		service.logout("missing-refresh-token");

		verify(sessionStore, never()).revokeSession("sid-1");
	}
}
