package shinhan.fibri.ieum.main.chat.service;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisChatMessageRateLimiter implements ChatMessageRateLimiter {

	private static final RedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = RedisScript.of("""
		local current = redis.call('INCR', KEYS[1])
		local ttl = redis.call('TTL', KEYS[1])
		if ttl < 0 then
			redis.call('EXPIRE', KEYS[1], ARGV[1])
		end
		return current
		""", Long.class);
	private static final int SEND_LIMIT = 10;
	private static final Duration WINDOW_TTL = Duration.ofSeconds(10);

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean tryConsumeSend(Long userId) {
		Long count = redisTemplate.execute(
			INCREMENT_WITH_TTL_SCRIPT,
			List.of("chat:rate:" + userId),
			String.valueOf(WINDOW_TTL.toSeconds())
		);
		return (count == null ? 0L : count) <= SEND_LIMIT;
	}
}
