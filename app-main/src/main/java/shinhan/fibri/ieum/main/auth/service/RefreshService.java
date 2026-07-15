package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.main.auth.dto.RefreshResponse;
import shinhan.fibri.ieum.main.auth.exception.InvalidRefreshTokenException;
import shinhan.fibri.ieum.main.auth.exception.RefreshTokenReusedException;
import shinhan.fibri.ieum.main.auth.session.AccessTokenIssuer;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.OpaqueTokenGenerator;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionCleanup;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@Service
@RequiredArgsConstructor
public class RefreshService {

	private static final Logger log = LoggerFactory.getLogger(RefreshService.class);

	private final RedisAuthSessionStore sessionStore;
	private final Sha256TokenHasher tokenHasher;
	private final OpaqueTokenGenerator tokenGenerator;
	private final AccessTokenIssuer accessTokenIssuer;
	private final WebPushSubscriptionCleanup webPushSubscriptionCleanup;
	private final SseConnectionRegistry sseConnectionRegistry;

	public RefreshResult refresh(String refreshToken) {
		String refreshTokenHash = tokenHasher.hash(refreshToken);
		AuthSession session = sessionStore.findByRefreshTokenHash(refreshTokenHash)
			.orElseThrow(InvalidRefreshTokenException::new);
		if (session.status() != UserStatus.active) {
			throw new InvalidRefreshTokenException();
		}
		if (refreshTokenHash.equals(session.prevRefreshTokenHash())) {
			log.warn("Refresh token reuse detected — revoking all sessions: userId={}", session.userId());
			runReuseInvalidation(
				"redis",
				session.userId(),
				() -> sessionStore.revokeAllSessionsOfUser(session.userId())
			);
			runReuseInvalidation(
				"push",
				session.userId(),
				() -> webPushSubscriptionCleanup.deleteForUser(session.userId())
			);
			runReuseInvalidation(
				"sse",
				session.userId(),
				() -> sseConnectionRegistry.closeUser(session.userId())
			);
			throw new RefreshTokenReusedException();
		}
		if (!refreshTokenHash.equals(session.refreshTokenHash())) {
			throw new InvalidRefreshTokenException();
		}

		String newRefreshToken = tokenGenerator.generate();
		String csrfToken = tokenGenerator.generate();
		String newRefreshTokenHash = tokenHasher.hash(newRefreshToken);
		String accessToken = accessTokenIssuer.issue(session.userId(), session.sessionId(), session.email(), session.role());
		sessionStore.rotateRefreshToken(session, newRefreshTokenHash);
		log.info("Token refreshed: userId={}", session.userId());

		return new RefreshResult(
			new RefreshResponse(session.userId(), session.role()),
			accessToken,
			newRefreshToken,
			csrfToken
		);
	}

	private void runReuseInvalidation(String action, Long userId, Runnable operation) {
		try {
			operation.run();
		}
		catch (RuntimeException exception) {
			log.warn(
				"Refresh reuse invalidation failed: action={} userId={} failureClass={}",
				action,
				userId,
				exception.getClass().getSimpleName()
			);
		}
	}
}
