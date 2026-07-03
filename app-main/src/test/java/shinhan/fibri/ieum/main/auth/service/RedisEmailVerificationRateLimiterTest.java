package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisEmailVerificationRateLimiterTest {

	@Test
	void tryConsumeSignupSendAllowsFirstRequestUsingAtomicTtlScript() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:send-count:minute:user@example.com")),
			eq("60")
		)).thenReturn(1L);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:send-count:day:user@example.com")),
			eq("86400")
		)).thenReturn(1L);
		RedisEmailVerificationRateLimiter rateLimiter = new RedisEmailVerificationRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryConsumeSignupSend("user@example.com")).isTrue();

		verify(redisTemplate).execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:send-count:minute:user@example.com")),
			eq("60")
		);
		verify(redisTemplate).execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:send-count:day:user@example.com")),
			eq("86400")
		);
	}

	@Test
	void tryConsumeSignupSendRejectsSecondRequestInSameMinute() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:send-count:minute:user@example.com")),
			eq("60")
		)).thenReturn(2L);
		RedisEmailVerificationRateLimiter rateLimiter = new RedisEmailVerificationRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryConsumeSignupSend("user@example.com")).isFalse();

		verify(redisTemplate, never()).execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:send-count:day:user@example.com")),
			eq("86400")
		);
	}

	@Test
	void tryConsumeSignupSendRejectsEleventhRequestInSameDay() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:send-count:minute:user@example.com")),
			eq("60")
		)).thenReturn(1L);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:send-count:day:user@example.com")),
			eq("86400")
		)).thenReturn(11L);
		RedisEmailVerificationRateLimiter rateLimiter = new RedisEmailVerificationRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryConsumeSignupSend("user@example.com")).isFalse();
	}

	@Test
	void tryConsumeSignupVerifyFailureAllowsFirstFiveFailuresUsingCodeTtl() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:verify-fail-count:user@example.com")),
			eq("180")
		)).thenReturn(5L);
		RedisEmailVerificationRateLimiter rateLimiter = new RedisEmailVerificationRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryConsumeSignupVerifyFailure("user@example.com")).isTrue();
	}

	@Test
	void tryConsumeSignupVerifyFailureRejectsSixthFailure() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			anyLongRedisScript(),
			eq(List.of("auth:email:signup:verify-fail-count:user@example.com")),
			eq("180")
		)).thenReturn(6L);
		RedisEmailVerificationRateLimiter rateLimiter = new RedisEmailVerificationRateLimiter(redisTemplate);

		assertThat(rateLimiter.tryConsumeSignupVerifyFailure("user@example.com")).isFalse();
	}

	@Test
	void clearSignupVerifyFailuresDeletesFailureCounter() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		RedisEmailVerificationRateLimiter rateLimiter = new RedisEmailVerificationRateLimiter(redisTemplate);

		rateLimiter.clearSignupVerifyFailures("user@example.com");

		verify(redisTemplate).delete("auth:email:signup:verify-fail-count:user@example.com");
	}

	private RedisScript<Long> anyLongRedisScript() {
		return any();
	}
}
