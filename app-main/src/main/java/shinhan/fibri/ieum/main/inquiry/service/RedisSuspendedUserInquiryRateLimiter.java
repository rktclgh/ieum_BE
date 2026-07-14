package shinhan.fibri.ieum.main.inquiry.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisSuspendedUserInquiryRateLimiter implements SuspendedUserInquiryRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisSuspendedUserInquiryRateLimiter.class);
	private static final int LIMIT_PER_MINUTE = 5;
	private static final String WINDOW_SECONDS = "60";
	private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
		local current = redis.call('INCR', KEYS[1])
		local ttl = redis.call('TTL', KEYS[1])
		if ttl < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
		return current
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public RedisSuspendedUserInquiryRateLimiter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public boolean tryAcquire(String clientIp) {
		try {
			Long count = redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key(clientIp)), WINDOW_SECONDS);
			return count != null && count <= LIMIT_PER_MINUTE;
		} catch (RuntimeException exception) {
			log.warn(
				"Suspended user inquiry rate limiter unavailable; allowing request. clientIp={}",
				clientIp,
				exception
			);
			return true;
		}
	}

	private String key(String clientIp) {
		return "inquiries:suspended-users:rl:" + clientIp;
	}
}
