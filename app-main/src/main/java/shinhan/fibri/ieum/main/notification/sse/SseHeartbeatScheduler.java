package shinhan.fibri.ieum.main.notification.sse;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.auth.session.CanonicalAuthStateVerifier;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

@Component
public class SseHeartbeatScheduler {
	private static final Logger log = LoggerFactory.getLogger(SseHeartbeatScheduler.class);

	private final SseConnectionRegistry registry;
	private final RedisAuthSessionStore sessionStore;
	private final CanonicalAuthStateVerifier canonicalAuthStateVerifier;
	private final NotificationProperties properties;
	private final AtomicLong tickCounter = new AtomicLong();

	public SseHeartbeatScheduler(
		SseConnectionRegistry registry,
		RedisAuthSessionStore sessionStore,
		CanonicalAuthStateVerifier canonicalAuthStateVerifier,
		NotificationProperties properties
	) {
		this.registry = registry;
		this.sessionStore = sessionStore;
		this.canonicalAuthStateVerifier = canonicalAuthStateVerifier;
		this.properties = properties;
	}

	@Scheduled(fixedRateString = "${ieum.notification.sse.heartbeat-ms:15000}")
	public void runHeartbeat() {
		registry.enqueueHeartbeat();

		int shardCount = properties.sessionCheckShards();
		int shard = Math.floorMod(tickCounter.getAndIncrement(), shardCount);
		for (SseSessionConnection connection : registry.activeSessionsInShard(shard, shardCount)) {
			try {
				if (!hasActiveSession(connection)) {
					registry.closeSession(connection.sessionId());
				}
			} catch (RuntimeException exception) {
				log.warn(
					"SSE session authorization revalidation failed; closing connection: userId={} cause={}",
					connection.userId(),
					exception.getClass().getSimpleName()
				);
				registry.closeSession(connection.sessionId());
			}
		}
	}

	private boolean hasActiveSession(SseSessionConnection connection) {
		return sessionStore.findBySessionId(connection.sessionId())
			.filter(session -> session.userId().equals(connection.userId()))
			.flatMap(canonicalAuthStateVerifier::findActiveMatching)
			.isPresent();
	}
}
