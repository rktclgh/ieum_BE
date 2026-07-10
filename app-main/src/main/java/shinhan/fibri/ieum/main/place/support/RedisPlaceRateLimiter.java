package shinhan.fibri.ieum.main.place.support;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisPlaceRateLimiter implements PlaceRateLimiter {

	private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
		local current = redis.call('INCR', KEYS[1])
		local ttl = redis.call('TTL', KEYS[1])
		if ttl < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
		return current
		""", Long.class);
	private final StringRedisTemplate redisTemplate;

	public RedisPlaceRateLimiter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public boolean tryAcquire(PlaceOperation operation, String clientKey) {
		try {
			Long count = redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key(operation, clientKey)), "60");
			return count != null && count <= limit(operation);
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private int limit(PlaceOperation operation) {
		return switch (operation) {
			case search -> 30;
			case geocode -> 20;
			case reverse -> 60;
		};
	}

	private String key(PlaceOperation operation, String clientKey) {
		return "places:rl:" + operation + ":" + clientKey;
	}
}
