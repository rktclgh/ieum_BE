package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;

@Service
@RequiredArgsConstructor
public class LogoutService {

	private final RedisAuthSessionStore sessionStore;
	private final Sha256TokenHasher tokenHasher;

	public void logout(String refreshToken) {
		String refreshTokenHash = tokenHasher.hash(refreshToken);
		sessionStore.findByRefreshTokenHash(refreshTokenHash)
			.map(AuthSession::sessionId)
			.ifPresent(sessionStore::revokeSession);
	}
}
