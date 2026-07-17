package shinhan.fibri.ieum.main.translation.service;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisTranslationRateLimiter implements TranslationRateLimiter {

	private static final RedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = RedisScript.of("""
		local current = redis.call('INCR', KEYS[1])
		local ttl = redis.call('TTL', KEYS[1])
		if ttl < 0 then
			redis.call('EXPIRE', KEYS[1], ARGV[1])
		end
		return current
		""", Long.class);
	private static final int REQUEST_LIMIT = 20;
	private static final Duration WINDOW_TTL = Duration.ofMinutes(1);

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean tryAcquire(Long userId) {
		try {
			Long count = redisTemplate.execute(
				INCREMENT_WITH_TTL_SCRIPT,
				List.of("translation:rate:" + userId),
				String.valueOf(WINDOW_TTL.toSeconds())
			);
			return (count == null ? Long.MAX_VALUE : count) <= REQUEST_LIMIT;
		} catch (RuntimeException exception) {
			return false;
		}
	}
}
