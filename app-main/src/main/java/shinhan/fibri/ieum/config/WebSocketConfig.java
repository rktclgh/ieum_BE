package shinhan.fibri.ieum.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
import shinhan.fibri.ieum.main.chat.websocket.ChatWebSocketHandshakeInterceptor;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final List<String> allowedOriginPatterns;
	private final ChatWebSocketHandshakeInterceptor handshakeInterceptor;

	public WebSocketConfig(
		@Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOrigins,
		ChatWebSocketHandshakeInterceptor handshakeInterceptor
	) {
		this.allowedOriginPatterns = csvValues(allowedOrigins);
		this.handshakeInterceptor = handshakeInterceptor;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws")
			.addInterceptors(handshakeInterceptor)
			.setAllowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new));
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

	private List<String> csvValues(String csv) {
		return Arrays.stream(csv.split(","))
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.toList();
	}
}
