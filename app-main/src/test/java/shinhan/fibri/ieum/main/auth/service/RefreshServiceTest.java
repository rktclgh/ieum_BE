package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.main.auth.exception.InvalidRefreshTokenException;
import shinhan.fibri.ieum.main.auth.exception.RefreshTokenReusedException;
import shinhan.fibri.ieum.main.auth.session.AccessTokenIssuer;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.OpaqueTokenGenerator;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;

class RefreshServiceTest {

	@Test
	void refreshRotatesCurrentRefreshTokenAndIssuesNewTokens() {
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
		RefreshService service = new RefreshService(
			sessionStore,
			tokenHasher,
			tokenGenerator,
			accessTokenIssuer
		);
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"old-refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		);
		when(tokenHasher.hash("refresh-token")).thenReturn("old-refresh-hash");
		when(sessionStore.findByRefreshTokenHash("old-refresh-hash")).thenReturn(Optional.of(session));
		when(tokenGenerator.generate()).thenReturn("new-refresh-token", "new-csrf-token");
		when(tokenHasher.hash("new-refresh-token")).thenReturn("new-refresh-hash");
		when(accessTokenIssuer.issue(42L, "sid-1", UserRole.user)).thenReturn("new-access-token");

		RefreshResult result = service.refresh("refresh-token");

		assertThat(result.response().userId()).isEqualTo(42L);
		assertThat(result.response().role()).isEqualTo(UserRole.user);
		assertThat(result.accessToken()).isEqualTo("new-access-token");
		assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
		assertThat(result.csrfToken()).isEqualTo("new-csrf-token");
		verify(sessionStore).rotateRefreshToken(session, "new-refresh-hash");
	}

	@Test
	void refreshThrowsInvalidRefreshTokenWhenSessionIsSuspended() {
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
		RefreshService service = new RefreshService(
			sessionStore,
			tokenHasher,
			tokenGenerator,
			accessTokenIssuer
		);
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.suspended,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		);
		when(tokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
		when(sessionStore.findByRefreshTokenHash("refresh-hash")).thenReturn(Optional.of(session));

		assertThatThrownBy(() -> service.refresh("refresh-token"))
			.isInstanceOf(InvalidRefreshTokenException.class);
		verify(sessionStore, never()).rotateRefreshToken(session, "new-refresh-hash");
	}

	@Test
	void refreshRevokesAllSessionsWhenPreviousRefreshTokenIsReused() {
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
		RefreshService service = new RefreshService(
			sessionStore,
			tokenHasher,
			tokenGenerator,
			accessTokenIssuer
		);
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"current-refresh-hash",
			"previous-refresh-hash",
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		);
		when(tokenHasher.hash("previous-refresh-token")).thenReturn("previous-refresh-hash");
		when(sessionStore.findByRefreshTokenHash("previous-refresh-hash")).thenReturn(Optional.of(session));

		assertThatThrownBy(() -> service.refresh("previous-refresh-token"))
			.isInstanceOf(RefreshTokenReusedException.class);
		verify(sessionStore).revokeAllSessionsOfUser(42L);
		verify(sessionStore, never()).rotateRefreshToken(session, "new-refresh-hash");
	}
}
