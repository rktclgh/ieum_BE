package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import shinhan.fibri.ieum.main.chat.websocket.ChatWebSocketHandshakeInterceptor;

class WebSocketConfigTest {

	private final ChatWebSocketHandshakeInterceptor handshakeInterceptor = org.mockito.Mockito.mock(ChatWebSocketHandshakeInterceptor.class);
	private final WebSocketConfig config = new WebSocketConfig(
		"http://localhost:3000, https://ieum.rktclgh.site",
		handshakeInterceptor
	);

	@Test
	void configuresWebSocketInfrastructureWithoutThrowing() {
		ExecutorSubscribableChannel inboundChannel = new ExecutorSubscribableChannel();
		ExecutorSubscribableChannel outboundChannel = new ExecutorSubscribableChannel();

		assertThatCode(() -> config.configureMessageBroker(new MessageBrokerRegistry(inboundChannel, outboundChannel)))
			.doesNotThrowAnyException();
		assertThatCode(() -> config.configureWebSocketTransport(new WebSocketTransportRegistration())).doesNotThrowAnyException();
		assertThatCode(() -> config.configureClientInboundChannel(new ChannelRegistration())).doesNotThrowAnyException();
		assertThatCode(() -> config.configureClientOutboundChannel(new ChannelRegistration())).doesNotThrowAnyException();
	}

	@Test
	void registersStompEndpointWithConfiguredOrigins() {
		StompEndpointRegistry registry = org.mockito.Mockito.mock(StompEndpointRegistry.class);
		StompWebSocketEndpointRegistration registration = org.mockito.Mockito.mock(StompWebSocketEndpointRegistration.class);
		when(registry.addEndpoint("/ws")).thenReturn(registration);
		when(registration.addInterceptors(handshakeInterceptor)).thenReturn(registration);

		assertThatCode(() -> config.registerStompEndpoints(registry)).doesNotThrowAnyException();
		verify(registration).addInterceptors(handshakeInterceptor);
		verify(registration).setAllowedOriginPatterns("http://localhost:3000", "https://ieum.rktclgh.site");
	}
}
