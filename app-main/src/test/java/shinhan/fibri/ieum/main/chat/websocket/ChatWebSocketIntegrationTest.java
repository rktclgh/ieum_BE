package shinhan.fibri.ieum.main.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.SendChatMessageRequest;
import shinhan.fibri.ieum.main.chat.service.ChatMessageRateLimiter;
import shinhan.fibri.ieum.main.chat.service.ChatMessageService;
import shinhan.fibri.ieum.main.chat.service.RoomEventPublisher;
import shinhan.fibri.ieum.main.chat.service.WsMessageEvent;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketIntegrationTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "chat_websocket");
	}

	@LocalServerPort
	private int port;

	@Autowired
	private SessionTokenValidator sessionTokenValidator;

	@Autowired
	private RedisAuthSessionStore sessionStore;

	@Autowired
	private ChatMemberRepository chatMemberRepository;

	@Autowired
	private ChatMessageRateLimiter rateLimiter;

	@Autowired
	private ChatMessageService chatMessageService;

	@Autowired
	private RoomEventPublisher roomEventPublisher;

	@Autowired
	private SimpUserRegistry userRegistry;

	@BeforeEach
	void setUp() {
		AuthenticatedUser principal = new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active);
		when(sessionTokenValidator.validateSession("access-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(principal, "sid-1")));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-08T00:00:00+09:00")
		)));
		when(rateLimiter.tryConsumeSend(42L)).thenReturn(true);
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);
		doAnswer(invocation -> {
			Long roomId = invocation.getArgument(1);
			SendChatMessageRequest request = invocation.getArgument(2);
			WsMessageEvent event = new WsMessageEvent(
				501L,
				roomId,
				42L,
				"user",
				request.content(),
				null,
				OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
			);
			Thread.ofPlatform().start(() -> roomEventPublisher.publish(event));
			return new ChatMessageResponse(501L, roomId, 42L, "user", request.content(), null, event.createdAt());
		}).when(chatMessageService).send(any(), any(), any());
	}

	@Test
	void websocketSendBroadcastsMessageToSubscribedRoom() throws Exception {
		StompSession session = connect();
		BlockingQueue<WsMessageEvent> messages = new LinkedBlockingQueue<>();
		session.subscribe("/topic/rooms/100", frameHandler(WsMessageEvent.class, messages));
		awaitSubscriptionRegistration("/topic/rooms/100");

		session.send("/app/rooms/100/send", new SendChatMessageRequest("hello", null));

		verify(chatMessageService, timeout(3000)).send(any(), eq(100L), any());
		WsMessageEvent message = messages.poll(3, TimeUnit.SECONDS);
		assertThat(message).isNotNull();
		assertThat(message.roomId()).isEqualTo(100L);
		assertThat(message.content()).isEqualTo("hello");
		session.disconnect();
	}

	@Test
	void websocketSubscribeSendsErrorWhenUserIsNotRoomMember() throws Exception {
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(200L, 42L)).thenReturn(false);
		StompSession session = connect();
		BlockingQueue<ChatWebSocketErrorResponse> errors = new LinkedBlockingQueue<>();
		session.subscribe("/user/queue/errors", frameHandler(ChatWebSocketErrorResponse.class, errors));
		awaitSubscriptionRegistration("/user/queue/errors");

		session.subscribe("/topic/rooms/200", frameHandler(WsMessageEvent.class, new LinkedBlockingQueue<>()));

		ChatWebSocketErrorResponse error = errors.poll(3, TimeUnit.SECONDS);
		assertThat(error).isEqualTo(new ChatWebSocketErrorResponse(
			"NOT_ROOM_MEMBER",
			"Room membership is required",
			200L
		));
		session.disconnect();
	}

	@SuppressWarnings("removal")
	private StompSession connect() throws Exception {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
		converter.getObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		client.setMessageConverter(converter);
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add(HttpHeaders.COOKIE, "access_token=access-token");
		StompSession session = client.connectAsync(webSocketUrl(), headers, new StompSessionHandlerAdapter() {
		}).get(3, TimeUnit.SECONDS);
		return session;
	}

	private String webSocketUrl() {
		return "ws://localhost:%d/ws".formatted(port);
	}

	private <T> StompFrameHandler frameHandler(Class<T> payloadType, BlockingQueue<T> values) {
		return new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return payloadType;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				values.add(payloadType.cast(payload));
			}
		};
	}

	private void awaitSubscriptionRegistration(String destination) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) {
			if (hasSubscription(destination)) {
				return;
			}
			Thread.sleep(50);
		}
		assertThat(hasSubscription(destination)).isTrue();
	}

	private boolean hasSubscription(String destination) {
		var user = userRegistry.getUser("42");
		if (user == null) {
			return false;
		}
		return user.getSessions().stream()
			.flatMap(session -> session.getSubscriptions().stream())
			.anyMatch(subscription -> destination.equals(subscription.getDestination()));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		SessionTokenValidator testSessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		@Primary
		RedisAuthSessionStore testSessionStore() {
			return mock(RedisAuthSessionStore.class);
		}

		@Bean
		@Primary
		ChatMemberRepository testChatMemberRepository() {
			return mock(ChatMemberRepository.class);
		}

		@Bean
		@Primary
		ChatMessageRateLimiter testChatMessageRateLimiter() {
			return mock(ChatMessageRateLimiter.class);
		}

		@Bean
		@Primary
		ChatMessageService testChatMessageService() {
			return mock(ChatMessageService.class);
		}
	}
}
