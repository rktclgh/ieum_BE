package shinhan.fibri.ieum.main.notification.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisRadiusNotificationGateTest {

	@Test
	void rejectsDuplicateOrOverloadedCellButFailsOpenWhenRedisFails() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(eq("notif:radius:sent:question:10"), eq("1"), any())).thenReturn(true, false);
		when(redis.execute(anyRedisScript(), eq(List.of("notif:radius:rl:wydm")), eq("60"))).thenReturn(30L, 31L);
		RedisRadiusNotificationGate gate = new RedisRadiusNotificationGate(redis);

		assertThat(gate.tryAcquire(NotificationCategory.question, 10L, "wydm")).isTrue();
		assertThat(gate.tryAcquire(NotificationCategory.question, 10L, "wydm")).isFalse();
	}

	@Test
	void allowsDeliveryWhenRedisIsUnavailable() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));

		assertThat(new RedisRadiusNotificationGate(redis).tryAcquire(NotificationCategory.meeting, 10L, "wydm")).isTrue();
	}

	@Test
	void rejectsEventWhenGeoCellRateLimitIsExceeded() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(any(), eq("1"), any())).thenReturn(true);
		when(redis.execute(anyRedisScript(), eq(List.of("notif:radius:rl:wydm")), eq("60"))).thenReturn(31L);

		assertThat(new RedisRadiusNotificationGate(redis).tryAcquire(NotificationCategory.question, 11L, "wydm")).isFalse();
	}

	private RedisScript<Long> anyRedisScript() { return any(); }
}
