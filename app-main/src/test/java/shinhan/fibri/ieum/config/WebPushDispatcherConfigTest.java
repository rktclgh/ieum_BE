package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.push.AsyncWebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.NoOpWebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushProviderClient;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionRepository;

class WebPushDispatcherConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(WebPushDispatcherConfig.class)
		.withBean(UserSettingsRepository.class, () -> mock(UserSettingsRepository.class))
		.withBean(WebPushSubscriptionRepository.class, () -> mock(WebPushSubscriptionRepository.class))
		.withBean(RedisAuthSessionStore.class, () -> mock(RedisAuthSessionStore.class))
		.withBean(WebPushProviderClient.class, () -> mock(WebPushProviderClient.class));

	@Test
	void disabledConfigurationCreatesOnlyNoOpDispatcherAndNoExecutor() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(WebPushDispatcher.class);
			assertThat(context.getBean(WebPushDispatcher.class)).isInstanceOf(NoOpWebPushDispatcher.class);
			assertThat(context).doesNotHaveBean("webPushTaskExecutor");
		});
	}

	@Test
	void enabledConfigurationCreatesBoundedAbortPolicyExecutorAndAsyncDispatcher() {
		contextRunner
			.withPropertyValues(
				"app.web-push.enabled=true",
				"app.web-push.executor.threads=2",
				"app.web-push.executor.queue=7",
				"app.web-push.executor.shutdown-seconds=3"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(WebPushDispatcher.class);
				assertThat(context.getBean(WebPushDispatcher.class)).isInstanceOf(AsyncWebPushDispatcher.class);
				ThreadPoolTaskExecutor executor = context.getBean("webPushTaskExecutor", ThreadPoolTaskExecutor.class);
				assertThat(executor.getCorePoolSize()).isEqualTo(2);
				assertThat(executor.getMaxPoolSize()).isEqualTo(2);
				assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(7);
				assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
					.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
			});
	}

	@Test
	void rejectsInvalidExecutorBounds() {
		contextRunner
			.withPropertyValues("app.web-push.executor.threads=0")
			.run(context -> assertThat(context).hasFailed());
		contextRunner
			.withPropertyValues("app.web-push.executor.queue=0")
			.run(context -> assertThat(context).hasFailed());
		contextRunner
			.withPropertyValues("app.web-push.executor.shutdown-seconds=-1")
			.run(context -> assertThat(context).hasFailed());
	}
}
