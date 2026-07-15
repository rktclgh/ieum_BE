package shinhan.fibri.ieum.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.push.AsyncWebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.NoOpWebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushExecutorProperties;
import shinhan.fibri.ieum.main.notification.push.WebPushProviderClient;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionRepository;

@Configuration
public class WebPushDispatcherConfig {

	@Bean
	WebPushExecutorProperties webPushExecutorProperties(
		@Value("${app.web-push.executor.threads:2}") int threads,
		@Value("${app.web-push.executor.queue:128}") int queueCapacity,
		@Value("${app.web-push.executor.shutdown-seconds:10}") int shutdownSeconds
	) {
		return new WebPushExecutorProperties(threads, queueCapacity, shutdownSeconds);
	}

	@Bean("webPushTaskExecutor")
	@ConditionalOnProperty(prefix = "app.web-push", name = "enabled", havingValue = "true")
	ThreadPoolTaskExecutor webPushTaskExecutor(WebPushExecutorProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.threads());
		executor.setMaxPoolSize(properties.threads());
		executor.setQueueCapacity(properties.queueCapacity());
		executor.setThreadNamePrefix("ieum-web-push-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(properties.shutdownSeconds());
		return executor;
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.web-push", name = "enabled", havingValue = "true")
	WebPushDispatcher asyncWebPushDispatcher(
		@Qualifier("webPushTaskExecutor") ThreadPoolTaskExecutor executor,
		UserSettingsRepository settingsRepository,
		WebPushSubscriptionRepository subscriptionRepository,
		RedisAuthSessionStore sessionStore,
		WebPushProviderClient providerClient
	) {
		return new AsyncWebPushDispatcher(
			executor,
			settingsRepository,
			subscriptionRepository,
			sessionStore,
			providerClient
		);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.web-push", name = "enabled", havingValue = "false", matchIfMissing = true)
	WebPushDispatcher noOpWebPushDispatcher() {
		return new NoOpWebPushDispatcher();
	}
}
