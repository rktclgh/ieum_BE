package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.main.notification.sse.NotificationProperties;

class NotificationConfigTest {

	@Test
	void createsDedicatedAbortPolicyNotificationExecutor() {
		NotificationProperties properties = properties();
		ThreadPoolTaskExecutor executor = new NotificationConfig().notificationTaskExecutor(properties);

		try {
			assertThat(executor.getCorePoolSize()).isEqualTo(4);
			assertThat(executor.getMaxPoolSize()).isEqualTo(16);
			assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(500);
			assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
				.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
		} finally {
			executor.shutdown();
		}
	}

	@Test
	void rejectsInvalidRetryRange() {
		assertThatThrownBy(() -> new NotificationProperties(
			1_800_000L,
			5,
			32,
			15_000L,
			4,
			8_000L,
			3_000L,
			4,
			16,
			500
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("retry");
	}

	private static NotificationProperties properties() {
		return new NotificationProperties(1_800_000L, 5, 32, 15_000L, 4, 3_000L, 8_000L, 4, 16, 500);
	}
}
