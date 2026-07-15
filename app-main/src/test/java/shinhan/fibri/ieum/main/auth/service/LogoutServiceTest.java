package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionCleanup;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

class LogoutServiceTest {

	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
	private final WebPushSubscriptionCleanup pushCleanup = mock(WebPushSubscriptionCleanup.class);
	private final SseConnectionRegistry registry = mock(SseConnectionRegistry.class);
	private LogoutService service;

	@BeforeEach
	void setUp() {
		service = new LogoutService(sessionStore, tokenHasher, pushCleanup, registry);
	}

	@Test
	void logoutInvalidatesRefreshAndAccessSessionsWhenTheyDiffer() {
		when(tokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
		when(sessionStore.findByRefreshTokenHash("refresh-hash"))
			.thenReturn(Optional.of(session("refresh-session")));

		service.logout("refresh-token", "access-session");

		InOrder order = inOrder(sessionStore, pushCleanup, registry);
		order.verify(sessionStore).findByRefreshTokenHash("refresh-hash");
		order.verify(sessionStore).revokeSession("refresh-session");
		order.verify(pushCleanup).deleteForSession("refresh-session");
		order.verify(registry).closeSession("refresh-session");
		order.verify(sessionStore).revokeSession("access-session");
		order.verify(pushCleanup).deleteForSession("access-session");
		order.verify(registry).closeSession("access-session");
	}

	@Test
	void logoutInvalidatesSameRefreshAndAccessSessionOnlyOnce() {
		when(tokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
		when(sessionStore.findByRefreshTokenHash("refresh-hash"))
			.thenReturn(Optional.of(session("same-session")));

		service.logout("refresh-token", "same-session");

		verify(sessionStore, times(1)).revokeSession("same-session");
		verify(pushCleanup, times(1)).deleteForSession("same-session");
		verify(registry, times(1)).closeSession("same-session");
	}

	@Test
	void logoutInvalidatesAccessSessionWithoutRefreshToken() {
		service.logout(null, "access-session");

		verifyNoInteractions(tokenHasher);
		verify(sessionStore).revokeSession("access-session");
		verify(pushCleanup).deleteForSession("access-session");
		verify(registry).closeSession("access-session");
	}

	@Test
	void logoutIsIdempotentWithoutEitherCredential() {
		service.logout(null, null);

		verifyNoInteractions(tokenHasher, sessionStore, pushCleanup, registry);
	}

	@Test
	void logoutStillInvalidatesValidatedAccessSessionWhenRefreshTokenDoesNotResolve() {
		when(tokenHasher.hash("missing-refresh-token")).thenReturn("missing-hash");
		when(sessionStore.findByRefreshTokenHash("missing-hash")).thenReturn(Optional.empty());

		service.logout("missing-refresh-token", "access-session");

		verify(sessionStore).revokeSession("access-session");
		verify(pushCleanup).deleteForSession("access-session");
		verify(registry).closeSession("access-session");
	}

	@Test
	void logoutIsolatesRedisPushAndSseFailures() {
		doThrow(new IllegalStateException("redis secret"))
			.when(sessionStore).revokeSession("access-session");
		doThrow(new IllegalStateException("database secret"))
			.when(pushCleanup).deleteForSession("access-session");
		doThrow(new IllegalStateException("sse secret"))
			.when(registry).closeSession("access-session");

		assertThatCode(() -> service.logout(null, "access-session"))
			.doesNotThrowAnyException();

		verify(sessionStore).revokeSession("access-session");
		verify(pushCleanup).deleteForSession("access-session");
		verify(registry).closeSession("access-session");
	}

	@Test
	void unresolvedRefreshTokenDoesNotInvalidateUntrustedSession() {
		when(tokenHasher.hash("missing-refresh-token")).thenReturn("missing-hash");
		when(sessionStore.findByRefreshTokenHash("missing-hash")).thenReturn(Optional.empty());

		service.logout("missing-refresh-token", null);

		verify(sessionStore, never()).revokeSession("untrusted-session");
		verifyNoInteractions(pushCleanup, registry);
	}

	private static AuthSession session(String sessionId) {
		return new AuthSession(
			sessionId,
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			0L
		);
	}
}
