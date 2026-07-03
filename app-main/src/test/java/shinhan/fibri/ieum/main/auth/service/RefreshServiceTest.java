package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
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
}
