package shinhan.fibri.ieum.main.notification.sse;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

@Component
public class SseHeartbeatScheduler {

	private final SseConnectionRegistry registry;
	private final RedisAuthSessionStore sessionStore;
	private final NotificationProperties properties;
	private final AtomicLong tickCounter = new AtomicLong();

	public SseHeartbeatScheduler(
		SseConnectionRegistry registry,
		RedisAuthSessionStore sessionStore,
		NotificationProperties properties
	) {
		this.registry = registry;
		this.sessionStore = sessionStore;
		this.properties = properties;
	}

	@Scheduled(fixedRateString = "${ieum.notification.sse.heartbeat-ms:15000}")
	public void runHeartbeat() {
		registry.enqueueHeartbeat();

		int shardCount = properties.sessionCheckShards();
		int shard = Math.floorMod(tickCounter.getAndIncrement(), shardCount);
		for (SseSessionConnection connection : registry.activeSessionsInShard(shard, shardCount)) {
			boolean valid = sessionStore.findBySessionId(connection.sessionId())
				.filter(session -> session.status() == UserStatus.active)
				.filter(session -> session.userId().equals(connection.userId()))
				.isPresent();
			if (!valid) {
				registry.closeSession(connection.sessionId());
			}
		}
	}
}
