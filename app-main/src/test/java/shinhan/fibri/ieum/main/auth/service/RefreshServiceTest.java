package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.auth.exception.InvalidRefreshTokenException;
import shinhan.fibri.ieum.main.auth.exception.RefreshTokenReusedException;
import shinhan.fibri.ieum.main.auth.session.AccessTokenIssuer;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.CanonicalAuthStateVerifier;
import shinhan.fibri.ieum.main.auth.session.OpaqueTokenGenerator;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.RefreshTokenRotationResult;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionCleanup;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

class RefreshServiceTest {

	@Test
	void refreshUsesCanonicalStateReturnedByVerifierAndReturnsOnlyAfterRotationWins() {
		CanonicalAuthStateVerifier canonicalAuthStateVerifier = mock(CanonicalAuthStateVerifier.class);
		Fixture fixture = new Fixture(canonicalAuthStateVerifier);
		AuthSession session = activeAdminSession();
		UserAuthState canonical = new UserAuthState(
			"canonical@example.com",
			UserRole.user,
			UserStatus.active,
			99L
		);
		when(fixture.tokenHasher.hash("refresh-token")).thenReturn("current-refresh-hash");
		when(fixture.sessionStore.findByRefreshTokenHash("current-refresh-hash"))
			.thenReturn(Optional.of(session));
		when(canonicalAuthStateVerifier.findActiveMatching(session)).thenReturn(Optional.of(canonical));
		when(fixture.tokenGenerator.generate()).thenReturn("new-refresh-token", "new-csrf-token");
		when(fixture.tokenHasher.hash("new-refresh-token")).thenReturn("new-refresh-hash");
		when(fixture.accessTokenIssuer.issue(
			42L,
			"sid-1",
			canonical.email(),
			canonical.role()
		)).thenReturn("new-access-token");
		when(fixture.sessionStore.compareAndRotateRefreshToken(
			session,
			"current-refresh-hash",
			"new-refresh-hash"
		)).thenReturn(RefreshTokenRotationResult.ROTATED);

		RefreshResult result = fixture.service.refresh("refresh-token");

		assertThat(result.response().userId()).isEqualTo(42L);
		assertThat(result.response().role()).isEqualTo(canonical.role());
		assertThat(result.accessToken()).isEqualTo("new-access-token");
		assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
		assertThat(result.csrfToken()).isEqualTo("new-csrf-token");
		InOrder order = inOrder(
			fixture.sessionStore,
			canonicalAuthStateVerifier,
			fixture.tokenGenerator,
			fixture.accessTokenIssuer
		);
		order.verify(fixture.sessionStore).findByRefreshTokenHash("current-refresh-hash");
		order.verify(canonicalAuthStateVerifier).findActiveMatching(session);
		order.verify(fixture.tokenGenerator, times(2)).generate();
		order.verify(fixture.accessTokenIssuer).issue(
			42L,
			"sid-1",
			canonical.email(),
			canonical.role()
		);
		order.verify(fixture.sessionStore).compareAndRotateRefreshToken(
			session,
			"current-refresh-hash",
			"new-refresh-hash"
		);
		verifyNoInteractions(fixture.userRepository);
		verify(fixture.webPushSubscriptionCleanup, never()).deleteForUser(42L);
	}

	@ParameterizedTest(name = "rejects stale refresh: {0}")
	@MethodSource("staleCanonicalStates")
	void refreshRejectsCanonicalMismatchEvenWhenSessionCleanupFails(
		String ignored,
		Optional<UserAuthState> canonicalState
	) {
		Fixture fixture = new Fixture();
		AuthSession session = activeAdminSession();
		when(fixture.tokenHasher.hash("refresh-token")).thenReturn("current-refresh-hash");
		when(fixture.sessionStore.findByRefreshTokenHash("current-refresh-hash"))
			.thenReturn(Optional.of(session));
		when(fixture.userRepository.findAuthStateById(42L)).thenReturn(canonicalState);
		doThrow(new IllegalStateException("redis unavailable"))
			.when(fixture.sessionStore)
			.revokeSession("sid-1");

		assertThatThrownBy(() -> fixture.service.refresh("refresh-token"))
			.isInstanceOf(InvalidRefreshTokenException.class);
		verify(fixture.sessionStore).revokeSession("sid-1");
		verify(fixture.sessionStore, never()).compareAndRotateRefreshToken(
			any(AuthSession.class),
			anyString(),
			anyString()
		);
		verifyNoInteractions(fixture.tokenGenerator, fixture.accessTokenIssuer);
	}

	@Test
	void refreshRejectsSuspendedSessionSnapshotBeforeCanonicalLookup() {
		Fixture fixture = new Fixture();
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"admin@example.com",
			"current-refresh-hash",
			null,
			UserRole.admin,
			UserStatus.suspended,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			7L
		);
		when(fixture.tokenHasher.hash("refresh-token")).thenReturn("current-refresh-hash");
		when(fixture.sessionStore.findByRefreshTokenHash("current-refresh-hash"))
			.thenReturn(Optional.of(session));

		assertThatThrownBy(() -> fixture.service.refresh("refresh-token"))
			.isInstanceOf(InvalidRefreshTokenException.class);
		verifyNoInteractions(fixture.userRepository, fixture.tokenGenerator, fixture.accessTokenIssuer);
	}

	@Test
	void previousRefreshTokenReuseEscalatesBeforeCanonicalRejection() {
		Fixture fixture = new Fixture();
		AuthSession staleCanonicalSession = new AuthSession(
			"sid-1",
			42L,
			"admin@example.com",
			"current-refresh-hash",
			"previous-refresh-hash",
			UserRole.admin,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			7L
		);
		when(fixture.tokenHasher.hash("previous-refresh-token")).thenReturn("previous-refresh-hash");
		when(fixture.sessionStore.findByRefreshTokenHash("previous-refresh-hash"))
			.thenReturn(Optional.of(staleCanonicalSession));

		assertThatThrownBy(() -> fixture.service.refresh("previous-refresh-token"))
			.isInstanceOf(RefreshTokenReusedException.class);
		InOrder order = inOrder(
			fixture.sessionStore,
			fixture.webPushSubscriptionCleanup,
			fixture.sseConnectionRegistry
		);
		order.verify(fixture.sessionStore).findByRefreshTokenHash("previous-refresh-hash");
		order.verify(fixture.sessionStore).revokeAllSessionsOfUser(42L);
		order.verify(fixture.webPushSubscriptionCleanup).deleteForUser(42L);
		order.verify(fixture.sseConnectionRegistry).closeUser(42L);
		verify(fixture.sessionStore, never()).revokeSession(anyString());
		verify(fixture.sessionStore, never()).compareAndRotateRefreshToken(
			any(AuthSession.class),
			anyString(),
			anyString()
		);
		verifyNoInteractions(
			fixture.userRepository,
			fixture.tokenGenerator,
			fixture.accessTokenIssuer
		);
	}

	@Test
	void concurrentRefreshLoserDetectedAsPreviousRevokesEveryUserSession() {
		Fixture fixture = new Fixture();
		AuthSession session = activeAdminSession();
		prepareRotation(fixture, session, RefreshTokenRotationResult.PREVIOUS);

		assertThatThrownBy(() -> fixture.service.refresh("refresh-token"))
			.isInstanceOf(RefreshTokenReusedException.class);

		InOrder order = inOrder(
			fixture.sessionStore,
			fixture.webPushSubscriptionCleanup,
			fixture.sseConnectionRegistry
		);
		order.verify(fixture.sessionStore).findByRefreshTokenHash("current-refresh-hash");
		order.verify(fixture.sessionStore).compareAndRotateRefreshToken(
			session,
			"current-refresh-hash",
			"new-refresh-hash"
		);
		order.verify(fixture.sessionStore).revokeAllSessionsOfUser(42L);
		order.verify(fixture.webPushSubscriptionCleanup).deleteForUser(42L);
		order.verify(fixture.sseConnectionRegistry).closeUser(42L);
		verify(fixture.sessionStore, never()).revokeSession(anyString());
	}

	@Test
	void concurrentRefreshMismatchReturnsInvalidWithoutRevocation() {
		Fixture fixture = new Fixture();
		AuthSession session = activeAdminSession();
		prepareRotation(fixture, session, RefreshTokenRotationResult.MISMATCH);

		assertThatThrownBy(() -> fixture.service.refresh("refresh-token"))
			.isInstanceOf(InvalidRefreshTokenException.class);

		verify(fixture.sessionStore).compareAndRotateRefreshToken(
			session,
			"current-refresh-hash",
			"new-refresh-hash"
		);
		verify(fixture.sessionStore, never()).revokeAllSessionsOfUser(any());
		verify(fixture.sessionStore, never()).revokeSession(anyString());
		verifyNoInteractions(fixture.webPushSubscriptionCleanup, fixture.sseConnectionRegistry);
	}

	@Test
	void rotationScriptFailureDoesNotReturnGeneratedTokens() {
		Fixture fixture = new Fixture();
		AuthSession session = activeAdminSession();
		prepareRotation(fixture, session, RefreshTokenRotationResult.ROTATED);
		when(fixture.sessionStore.compareAndRotateRefreshToken(
			session,
			"current-refresh-hash",
			"new-refresh-hash"
		)).thenThrow(new IllegalStateException("redis script failed"));

		assertThatThrownBy(() -> fixture.service.refresh("refresh-token"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("redis script failed");

		verify(fixture.sessionStore, never()).revokeAllSessionsOfUser(any());
		verify(fixture.sessionStore, never()).revokeSession(anyString());
		verifyNoInteractions(fixture.webPushSubscriptionCleanup, fixture.sseConnectionRegistry);
	}

	@Test
	void refreshReuseAttemptsEveryInvalidationAndAlwaysThrowsReuseException() {
		Fixture fixture = new Fixture();
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"admin@example.com",
			"current-refresh-hash",
			"previous-refresh-hash",
			UserRole.admin,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			7L
		);
		when(fixture.tokenHasher.hash("previous-refresh-token")).thenReturn("previous-refresh-hash");
		when(fixture.sessionStore.findByRefreshTokenHash("previous-refresh-hash"))
			.thenReturn(Optional.of(session));
		doThrow(new IllegalStateException("redis secret"))
			.when(fixture.sessionStore).revokeAllSessionsOfUser(42L);
		doThrow(new IllegalStateException("database secret"))
			.when(fixture.webPushSubscriptionCleanup).deleteForUser(42L);
		doThrow(new IllegalStateException("sse secret"))
			.when(fixture.sseConnectionRegistry).closeUser(42L);

		assertThatThrownBy(() -> fixture.service.refresh("previous-refresh-token"))
			.isInstanceOf(RefreshTokenReusedException.class);

		verify(fixture.sessionStore).revokeAllSessionsOfUser(42L);
		verify(fixture.webPushSubscriptionCleanup).deleteForUser(42L);
		verify(fixture.sseConnectionRegistry).closeUser(42L);
	}

	private static void prepareRotation(
		Fixture fixture,
		AuthSession session,
		RefreshTokenRotationResult rotationResult
	) {
		UserAuthState canonical = activeAdminState();
		when(fixture.tokenHasher.hash("refresh-token")).thenReturn("current-refresh-hash");
		when(fixture.sessionStore.findByRefreshTokenHash("current-refresh-hash"))
			.thenReturn(Optional.of(session));
		when(fixture.userRepository.findAuthStateById(42L)).thenReturn(Optional.of(canonical));
		when(fixture.tokenGenerator.generate()).thenReturn("new-refresh-token", "new-csrf-token");
		when(fixture.tokenHasher.hash("new-refresh-token")).thenReturn("new-refresh-hash");
		when(fixture.accessTokenIssuer.issue(
			42L,
			"sid-1",
			canonical.email(),
			canonical.role()
		)).thenReturn("new-access-token");
		when(fixture.sessionStore.compareAndRotateRefreshToken(
			session,
			"current-refresh-hash",
			"new-refresh-hash"
		)).thenReturn(rotationResult);
	}

	private static Stream<Arguments> staleCanonicalStates() {
		return Stream.of(
			Arguments.of(
				"database user is suspended",
				Optional.of(new UserAuthState(
					"admin@example.com",
					UserRole.admin,
					UserStatus.suspended,
					7L
				))
			),
			Arguments.of(
				"database role was demoted",
				Optional.of(new UserAuthState(
					"admin@example.com",
					UserRole.user,
					UserStatus.active,
					7L
				))
			),
			Arguments.of(
				"database auth version advanced",
				Optional.of(new UserAuthState(
					"admin@example.com",
					UserRole.admin,
					UserStatus.active,
					8L
				))
			),
			Arguments.of("database user was deleted or is missing", Optional.empty())
		);
	}

	private static AuthSession activeAdminSession() {
		return new AuthSession(
			"sid-1",
			42L,
			"admin@example.com",
			"current-refresh-hash",
			null,
			UserRole.admin,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			7L
		);
	}

	private static UserAuthState activeAdminState() {
		return new UserAuthState(
			"admin@example.com",
			UserRole.admin,
			UserStatus.active,
			7L
		);
	}

	private static class Fixture {

		private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		private final UserRepository userRepository = mock(UserRepository.class);
		private final Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
		private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		private final AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
		private final WebPushSubscriptionCleanup webPushSubscriptionCleanup =
			mock(WebPushSubscriptionCleanup.class);
		private final SseConnectionRegistry sseConnectionRegistry = mock(SseConnectionRegistry.class);
		private final RefreshService service;

		private Fixture() {
			this.service = service(new CanonicalAuthStateVerifier(userRepository));
		}

		private Fixture(CanonicalAuthStateVerifier canonicalAuthStateVerifier) {
			this.service = service(canonicalAuthStateVerifier);
		}

		private RefreshService service(CanonicalAuthStateVerifier canonicalAuthStateVerifier) {
			return new RefreshService(
				sessionStore,
				canonicalAuthStateVerifier,
				tokenHasher,
				tokenGenerator,
				accessTokenIssuer,
				webPushSubscriptionCleanup,
				sseConnectionRegistry
			);
		}
	}
}
