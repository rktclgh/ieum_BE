package shinhan.fibri.ieum.main.auth.service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionCleanup;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@Service
@RequiredArgsConstructor
public class LogoutService {

	private static final Logger log = LoggerFactory.getLogger(LogoutService.class);

	private final RedisAuthSessionStore sessionStore;
	private final Sha256TokenHasher tokenHasher;
	private final WebPushSubscriptionCleanup webPushSubscriptionCleanup;
	private final SseConnectionRegistry sseConnectionRegistry;

	public void logout(String refreshToken, String authenticatedSessionId) {
		Set<String> sessionIds = new LinkedHashSet<>();
		resolveRefreshSessionId(refreshToken).ifPresent(sessionIds::add);
		if (isValidSessionId(authenticatedSessionId)) {
			sessionIds.add(authenticatedSessionId);
		}

		for (String sessionId : sessionIds) {
			runIsolated("redis", () -> sessionStore.revokeSession(sessionId));
			runIsolated("push", () -> webPushSubscriptionCleanup.deleteForSession(sessionId));
			runIsolated("sse", () -> sseConnectionRegistry.closeSession(sessionId));
		}
		log.info("Logout invalidation completed: sessionCount={}", sessionIds.size());
	}

	private Optional<String> resolveRefreshSessionId(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			return Optional.empty();
		}
		try {
			String refreshTokenHash = tokenHasher.hash(refreshToken);
			return sessionStore.findByRefreshTokenHash(refreshTokenHash)
				.map(AuthSession::sessionId)
				.filter(this::isValidSessionId);
		}
		catch (RuntimeException exception) {
			log.warn(
				"Logout refresh credential resolution failed: failureClass={}",
				exception.getClass().getSimpleName()
			);
			return Optional.empty();
		}
	}

	private boolean isValidSessionId(String sessionId) {
		return sessionId != null && !sessionId.isBlank() && sessionId.length() <= 64;
	}

	private void runIsolated(String action, Runnable operation) {
		try {
			operation.run();
		}
		catch (RuntimeException exception) {
			log.warn(
				"Logout invalidation action failed: action={} failureClass={}",
				action,
				exception.getClass().getSimpleName()
			);
		}
	}
}
