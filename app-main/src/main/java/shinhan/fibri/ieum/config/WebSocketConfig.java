package shinhan.fibri.ieum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws")
			.setAllowedOriginPatterns("http://localhost:3000", "https://ieum.rktclgh.site");
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic", "/queue")
			.setHeartbeatValue(new long[] {10000, 10000})
			.setTaskScheduler(chatWebSocketTaskScheduler());
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
	}

	@Bean
	public TaskScheduler chatWebSocketTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("chat-ws-heartbeat-");
		return scheduler;
	}

	@Override
	public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
		registry.setMessageSizeLimit(64 * 1024);
		registry.setSendBufferSizeLimit(512 * 1024);
		registry.setSendTimeLimit(10_000);
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.taskExecutor()
			.corePoolSize(4)
			.maxPoolSize(8)
			.queueCapacity(1000);
	}

	@Override
	public void configureClientOutboundChannel(ChannelRegistration registration) {
		registration.taskExecutor()
			.corePoolSize(4)
			.maxPoolSize(8)
			.queueCapacity(1000);
	}
}
