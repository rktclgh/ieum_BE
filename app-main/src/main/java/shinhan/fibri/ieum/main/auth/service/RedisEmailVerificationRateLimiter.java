package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisEmailVerificationRateLimiter implements EmailVerificationRateLimiter {

	private static final RedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = RedisScript.of("""
		local current = redis.call('INCR', KEYS[1])
		local ttl = redis.call('TTL', KEYS[1])
		if ttl < 0 then
			redis.call('EXPIRE', KEYS[1], ARGV[1])
		end
		return current
		""", Long.class);

	private static final String SIGNUP_SEND_COUNT_KEY_PREFIX = "auth:email:signup:send-count:";
	private static final String SIGNUP_VERIFY_FAIL_COUNT_KEY_PREFIX = "auth:email:signup:verify-fail-count:";
	private static final int MINUTE_LIMIT = 1;
	private static final int DAILY_LIMIT = 10;
	private static final int VERIFY_FAILURE_LIMIT = 5;
	private static final Duration MINUTE_TTL = Duration.ofMinutes(1);
	private static final Duration DAILY_TTL = Duration.ofDays(1);
	private static final Duration VERIFY_FAILURE_TTL = Duration.ofMinutes(3);

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean tryConsumeSignupSend(String email) {
		long minuteCount = incrementWithExpiry(minuteKey(email), MINUTE_TTL);
		if (minuteCount > MINUTE_LIMIT) {
			return false;
		}

		long dailyCount = incrementWithExpiry(dayKey(email), DAILY_TTL);
		return dailyCount <= DAILY_LIMIT;
	}

	@Override
	public boolean tryConsumeSignupVerifyFailure(String email) {
		long failureCount = incrementWithExpiry(verifyFailureKey(email), VERIFY_FAILURE_TTL);
		return failureCount <= VERIFY_FAILURE_LIMIT;
	}

	@Override
	public void clearSignupVerifyFailures(String email) {
		redisTemplate.delete(verifyFailureKey(email));
	}

	private long incrementWithExpiry(String key, Duration ttl) {
		Long count = redisTemplate.execute(
			INCREMENT_WITH_TTL_SCRIPT,
			List.of(key),
			String.valueOf(ttl.toSeconds())
		);
		return count == null ? 0L : count;
	}

	private String minuteKey(String email) {
		return SIGNUP_SEND_COUNT_KEY_PREFIX + "minute:" + email;
	}

	private String dayKey(String email) {
		return SIGNUP_SEND_COUNT_KEY_PREFIX + "day:" + email;
	}

	private String verifyFailureKey(String email) {
		return SIGNUP_VERIFY_FAIL_COUNT_KEY_PREFIX + email;
	}
}
