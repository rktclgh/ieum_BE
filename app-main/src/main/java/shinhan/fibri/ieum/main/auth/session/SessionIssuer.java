package shinhan.fibri.ieum.main.auth.session;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.User;

@Component
@RequiredArgsConstructor
public class SessionIssuer {

	private final OpaqueTokenGenerator tokenGenerator;
	private final Sha256TokenHasher tokenHasher;
	private final AccessTokenIssuer accessTokenIssuer;
	private final RedisAuthSessionStore sessionStore;

	public IssuedAuthSession issue(User user) {
		String sessionId = tokenGenerator.generate();
		String refreshToken = tokenGenerator.generate();
		String csrfToken = tokenGenerator.generate();
		String refreshTokenHash = tokenHasher.hash(refreshToken);
		String accessToken = accessTokenIssuer.issue(user.getId(), sessionId, user.getEmail(), user.getRole());
		AuthSession session = new AuthSession(
			sessionId,
			user.getId(),
			user.getEmail(),
			refreshTokenHash,
			null,
			user.getRole(),
			user.getStatus(),
			OffsetDateTime.now(ZoneOffset.UTC),
			user.getAuthVersion()
		);
		writeSessionAfterCommit(session);
		return new IssuedAuthSession(accessToken, refreshToken, csrfToken);
	}

	private void writeSessionAfterCommit(AuthSession session) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					sessionStore.create(session);
				}
			});
			return;
		}
		sessionStore.create(session);
	}
}
