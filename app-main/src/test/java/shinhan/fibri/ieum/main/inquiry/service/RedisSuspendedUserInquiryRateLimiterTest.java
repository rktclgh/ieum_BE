package shinhan.fibri.ieum.main.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(OutputCaptureExtension.class)
class RedisSuspendedUserInquiryRateLimiterTest {

	@Test
	void allowsFifthRequestInMinuteAndRejectsSixth() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("inquiries:suspended-users:rl:203.0.113.10")),
			eq("60")
		)).thenReturn(5L, 6L);
		RedisSuspendedUserInquiryRateLimiter rateLimiter = new RedisSuspendedUserInquiryRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryAcquire("203.0.113.10")).isTrue();
		assertThat(rateLimiter.tryAcquire("203.0.113.10")).isFalse();
	}

	@Test
	void failsOpenAndLogsWhenRedisIsUnavailable(CapturedOutput output) {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(anyLongRedisScript(), any(), eq("60")))
			.thenThrow(new IllegalStateException("redis unavailable"));
		RedisSuspendedUserInquiryRateLimiter rateLimiter = new RedisSuspendedUserInquiryRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryAcquire("203.0.113.10")).isTrue();
		assertThat(output.getOut())
			.contains("Suspended user inquiry rate limiter unavailable; allowing request")
			.contains("clientIp=203.0.113.10");
	}

	private RedisScript<Long> anyLongRedisScript() {
		return any();
	}
}
