package shinhan.fibri.ieum.main.place.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisPlaceRateLimiterTest {

	@Test
	void allowsAtLimitAndRejectsNextRequest() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(anyRedisScript(), eq(List.of("places:rl:search:client-hash")), eq("60"))).thenReturn(30L, 31L);
		RedisPlaceRateLimiter limiter = new RedisPlaceRateLimiter(redis);

		assertThat(limiter.tryAcquire(PlaceOperation.search, "client-hash")).isTrue();
		assertThat(limiter.tryAcquire(PlaceOperation.search, "client-hash")).isFalse();
	}

	@Test
	void failsClosedWhenRedisIsUnavailable() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(anyRedisScript(), any(), eq("60"))).thenThrow(new IllegalStateException("redis unavailable"));

		assertThat(new RedisPlaceRateLimiter(redis).tryAcquire(PlaceOperation.geocode, "client-hash")).isFalse();
	}

	private RedisScript<Long> anyRedisScript() {
		return any();
	}
}
