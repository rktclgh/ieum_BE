package shinhan.fibri.ieum.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.main.notification.sse.NotificationProperties;

@Configuration
public class NotificationConfig {

	@Bean
	NotificationProperties notificationProperties(
		@Value("${ieum.notification.sse.timeout-ms:1800000}") long sseTimeoutMs,
		@Value("${ieum.notification.sse.max-conn-per-user:5}") int maxConnectionsPerUser,
		@Value("${ieum.notification.sse.durable-queue-per-emitter:32}") int durableQueuePerEmitter,
		@Value("${ieum.notification.sse.heartbeat-ms:15000}") long heartbeatMs,
		@Value("${ieum.notification.sse.session-check-shards:4}") int sessionCheckShards,
		@Value("${ieum.notification.sse.retry-min-ms:3000}") long retryMinMs,
		@Value("${ieum.notification.sse.retry-max-ms:8000}") long retryMaxMs,
		@Value("${ieum.notification.dispatch.core:4}") int dispatchCorePoolSize,
		@Value("${ieum.notification.dispatch.max:16}") int dispatchMaxPoolSize,
		@Value("${ieum.notification.dispatch.queue:500}") int dispatchQueueCapacity
	) {
		return new NotificationProperties(
			sseTimeoutMs,
			maxConnectionsPerUser,
			durableQueuePerEmitter,
			heartbeatMs,
			sessionCheckShards,
			retryMinMs,
			retryMaxMs,
			dispatchCorePoolSize,
			dispatchMaxPoolSize,
			dispatchQueueCapacity
		);
	}

	@Bean("notificationTaskExecutor")
	ThreadPoolTaskExecutor notificationTaskExecutor(NotificationProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ieum-notification-");
		executor.setCorePoolSize(properties.dispatchCorePoolSize());
		executor.setMaxPoolSize(properties.dispatchMaxPoolSize());
		executor.setQueueCapacity(properties.dispatchQueueCapacity());
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
