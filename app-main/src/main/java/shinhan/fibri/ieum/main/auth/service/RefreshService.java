package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.main.auth.dto.RefreshResponse;
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

@Service
@RequiredArgsConstructor
public class RefreshService {

	private static final Logger log = LoggerFactory.getLogger(RefreshService.class);

	private final RedisAuthSessionStore sessionStore;
	private final CanonicalAuthStateVerifier canonicalAuthStateVerifier;
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
			throw revokeAllSessionsForReuse(session);
		}
		if (!refreshTokenHash.equals(session.refreshTokenHash())) {
			throw new InvalidRefreshTokenException();
		}
		UserAuthState canonical = canonicalAuthStateVerifier.findActiveMatching(session)
			.orElse(null);
		if (canonical == null) {
			revokeStaleSession(session);
			throw new InvalidRefreshTokenException();
		}

		String newRefreshToken = tokenGenerator.generate();
		String csrfToken = tokenGenerator.generate();
		String newRefreshTokenHash = tokenHasher.hash(newRefreshToken);
		String accessToken = accessTokenIssuer.issue(
			session.userId(),
			session.sessionId(),
			canonical.email(),
			canonical.role()
		);
		RefreshTokenRotationResult rotationResult = sessionStore.compareAndRotateRefreshToken(
			session,
			refreshTokenHash,
			newRefreshTokenHash
		);
		if (rotationResult == RefreshTokenRotationResult.PREVIOUS) {
			throw revokeAllSessionsForReuse(session);
		}
		if (rotationResult == RefreshTokenRotationResult.MISMATCH) {
			throw new InvalidRefreshTokenException();
		}
		log.info("Token refreshed: userId={} sessionId={}", session.userId(), session.sessionId());

		return new RefreshResult(
			new RefreshResponse(session.userId(), canonical.role()),
			accessToken,
			newRefreshToken,
			csrfToken
		);
	}

	private RefreshTokenReusedException revokeAllSessionsForReuse(AuthSession session) {
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
		return new RefreshTokenReusedException();
	}

	private void revokeStaleSession(AuthSession session) {
		try {
			sessionStore.revokeSession(session.sessionId());
		} catch (RuntimeException exception) {
			log.warn("Failed to revoke stale auth session: userId={}", session.userId(), exception);
		}
	}

	private void runReuseInvalidation(String action, Long userId, Runnable operation) {
		try {
			operation.run();
		} catch (RuntimeException exception) {
			log.warn(
				"Refresh reuse invalidation failed: action={} userId={} failureClass={}",
				action,
				userId,
				exception.getClass().getSimpleName()
			);
		}
	}
}
