package shinhan.fibri.ieum.main.translation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings({"unchecked", "rawtypes"})
class RedisTranslationRateLimiterTest {

	@Test
	void allowsTwentiethRequestAndRejectsTwentyFirstInFixedWindow() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(any(RedisScript.class), eq(java.util.List.of("translation:rate:42")), eq("60")))
			.thenReturn(20L)
			.thenReturn(21L);
		RedisTranslationRateLimiter limiter = new RedisTranslationRateLimiter(redisTemplate);

		assertThat(limiter.tryAcquire(42L)).isTrue();
		assertThat(limiter.tryAcquire(42L)).isFalse();
	}

	@Test
	void failsClosedOnRedisFailure() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(any(RedisScript.class), any(), any()))
			.thenThrow(new RedisConnectionFailureException("down"));
		RedisTranslationRateLimiter limiter = new RedisTranslationRateLimiter(redisTemplate);

		assertThat(limiter.tryAcquire(42L)).isFalse();
	}
}
