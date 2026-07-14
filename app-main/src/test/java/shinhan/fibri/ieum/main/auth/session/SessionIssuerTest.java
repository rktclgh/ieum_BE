package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;

class SessionIssuerTest {

	@Test
	void issueCreatesTokensAndWritesSessionAfterCommit() {
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
		AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		SessionIssuer issuer = new SessionIssuer(tokenGenerator, tokenHasher, accessTokenIssuer, sessionStore);
		User user = user();
		when(tokenGenerator.generate()).thenReturn("sid-1", "refresh-token", "csrf-token");
		when(tokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
		when(accessTokenIssuer.issue(42L, "sid-1", "user@example.com", UserRole.user)).thenReturn("access-token");

		TransactionSynchronizationManager.initSynchronization();
		try {
			IssuedAuthSession issued = issuer.issue(user);

			assertThat(issued.accessToken()).isEqualTo("access-token");
			assertThat(issued.refreshToken()).isEqualTo("refresh-token");
			assertThat(issued.csrfToken()).isEqualTo("csrf-token");
			verify(sessionStore, never()).create(org.mockito.ArgumentMatchers.any(AuthSession.class));

			for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
				synchronization.afterCommit();
			}

			ArgumentCaptor<AuthSession> sessionCaptor = ArgumentCaptor.forClass(AuthSession.class);
			verify(sessionStore).create(sessionCaptor.capture());
			AuthSession session = sessionCaptor.getValue();
			assertThat(session.sessionId()).isEqualTo("sid-1");
			assertThat(session.userId()).isEqualTo(42L);
			assertThat(session.email()).isEqualTo("user@example.com");
			assertThat(session.refreshTokenHash()).isEqualTo("refresh-hash");
			assertThat(session.role()).isEqualTo(UserRole.user);
			assertThat(session.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private User user() {
		User user = User.createEmailUser(
			"user@example.com",
			"hashed-password",
			"nickname",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
		ReflectionTestUtils.setField(user, "id", 42L);
		return user;
	}
}
