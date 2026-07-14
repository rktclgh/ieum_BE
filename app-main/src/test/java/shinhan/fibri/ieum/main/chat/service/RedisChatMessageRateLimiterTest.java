package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisChatMessageRateLimiterTest {

	@Test
	void tryConsumeSendAllowsTenthMessageInWindow() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("chat:rate:42")),
			eq("10")
		)).thenReturn(10L);
		RedisChatMessageRateLimiter rateLimiter = new RedisChatMessageRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryConsumeSend(42L)).isTrue();
	}

	@Test
	void tryConsumeSendRejectsEleventhMessageInWindow() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("chat:rate:42")),
			eq("10")
		)).thenReturn(11L);
		RedisChatMessageRateLimiter rateLimiter = new RedisChatMessageRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryConsumeSend(42L)).isFalse();
	}

	private RedisScript<Long> anyLongRedisScript() {
		return any();
	}
}
