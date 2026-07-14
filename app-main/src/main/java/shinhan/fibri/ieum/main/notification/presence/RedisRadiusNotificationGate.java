package shinhan.fibri.ieum.main.notification.presence;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisRadiusNotificationGate implements RadiusNotificationGate {

	private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
		local current = redis.call('INCR', KEYS[1])
		local ttl = redis.call('TTL', KEYS[1])
		if ttl < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
		return current
		""", Long.class);
	private static final Duration DEDUP_TTL = Duration.ofMinutes(5);
	private static final int CELL_LIMIT = 30;
	private static final String CELL_TTL_SECONDS = "60";
	private final StringRedisTemplate redisTemplate;

	public RedisRadiusNotificationGate(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public boolean tryAcquire(NotificationCategory category, Long refId, String geoHash) {
		try {
			Boolean first = redisTemplate.opsForValue().setIfAbsent(dedupKey(category, refId), "1", DEDUP_TTL);
			if (!Boolean.TRUE.equals(first)) return false;
			Long count = redisTemplate.execute(INCREMENT_WITH_TTL, List.of(cellKey(geoHash)), CELL_TTL_SECONDS);
			return (count == null ? 0L : count) <= CELL_LIMIT;
		} catch (RuntimeException exception) {
			return true;
		}
	}

	private String dedupKey(NotificationCategory category, Long refId) { return "notif:radius:sent:" + category + ":" + refId; }
	private String cellKey(String geoHash) { return "notif:radius:rl:" + geoHash; }
}
