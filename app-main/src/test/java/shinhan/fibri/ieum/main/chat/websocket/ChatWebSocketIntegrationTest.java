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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.admin.content.service.ContentPurgeService;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.CanonicalAuthStateVerifier;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomListEvent;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomSummaryResponse;
import shinhan.fibri.ieum.main.chat.dto.SendChatMessageRequest;
import shinhan.fibri.ieum.main.chat.service.ChatRoomListEventPublisher;
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
	private CanonicalAuthStateVerifier canonicalAuthStateVerifier;

	@Autowired
	private ChatMemberRepository chatMemberRepository;

	@Autowired
	private ChatMessageRateLimiter rateLimiter;

	@Autowired
	private ChatMessageService chatMessageService;

	@Autowired
	private RoomEventPublisher roomEventPublisher;

	@Autowired
	private ChatRoomListEventPublisher roomListEventPublisher;

	@Autowired
	private SimpUserRegistry userRegistry;

	@MockitoBean
	private ContentPurgeService contentPurgeService;

	@BeforeEach
	void setUp() {
		AuthenticatedUser principal = new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active);
		AuthenticatedUser otherPrincipal = new AuthenticatedUser(77L, "other@example.com", UserRole.user, UserStatus.active);
		AuthSession authSession = new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-08T00:00:00+09:00"),
			0L
		);
		AuthSession otherAuthSession = new AuthSession(
			"sid-2",
			77L,
			"other@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-08T00:00:00+09:00"),
			0L
		);
		when(sessionTokenValidator.validateSession("access-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(principal, "sid-1")));
		when(sessionTokenValidator.validateSession("other-access-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(otherPrincipal, "sid-2")));
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(authSession));
		when(sessionStore.findBySessionId("sid-2")).thenReturn(Optional.of(otherAuthSession));
		when(canonicalAuthStateVerifier.findActiveMatching(authSession)).thenReturn(Optional.of(new UserAuthState(
			"user@example.com",
			UserRole.user,
			UserStatus.active,
			0L
		)));
		when(canonicalAuthStateVerifier.findActiveMatching(otherAuthSession)).thenReturn(Optional.of(new UserAuthState(
			"other@example.com",
			UserRole.user,
			UserStatus.active,
			0L
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
				"/api/v1/files/11111111-1111-1111-1111-111111111111",
				MessageType.user,
				request.content(),
				null,
				OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
			);
			Thread.ofPlatform().start(() -> roomEventPublisher.publish(event));
			return new ChatMessageResponse(
				501L,
				roomId,
				42L,
				"user",
				"/api/v1/files/11111111-1111-1111-1111-111111111111",
				MessageType.user,
				request.content(),
				null,
				event.createdAt()
			);
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
		assertThat(message.senderProfileImageUrl()).isEqualTo("/api/v1/files/11111111-1111-1111-1111-111111111111");
		assertThat(message.messageType()).isEqualTo(MessageType.user);
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

	@Test
	void userRoomQueueReceivesUpsertEventForAuthenticatedUser() throws Exception {
		StompSession session = connect();
		BlockingQueue<ChatRoomListEvent> events = new LinkedBlockingQueue<>();
		session.subscribe("/user/queue/rooms", frameHandler(ChatRoomListEvent.class, events));
		awaitSubscriptionRegistration("42", "/user/queue/rooms");

		roomListEventPublisher.publish(42L, ChatRoomListEvent.upsert(roomSummary(100L, true)));
		ChatRoomListEvent upsert = events.poll(3, TimeUnit.SECONDS);
		assertThat(upsert).isNotNull();
		assertThat(upsert.type()).isEqualTo("upsert");
		assertThat(upsert.room()).isNotNull();
		assertThat(upsert.room().roomId()).isEqualTo(100L);
		assertThat(upsert.room().pinned()).isTrue();
		assertThat(upsert.roomId()).isNull();
		session.disconnect();
	}

	@Test
	void userRoomQueueReceivesRemoveEventForAuthenticatedUser() throws Exception {
		StompSession session = connect();
		BlockingQueue<ChatRoomListEvent> events = new LinkedBlockingQueue<>();
		session.subscribe("/user/queue/rooms", frameHandler(ChatRoomListEvent.class, events));
		awaitSubscriptionRegistration("42", "/user/queue/rooms");

		roomListEventPublisher.publish(42L, ChatRoomListEvent.remove(100L));
		ChatRoomListEvent remove = events.poll(3, TimeUnit.SECONDS);
		assertThat(remove).isNotNull();
		assertThat(remove.type()).isEqualTo("remove");
		assertThat(remove.room()).isNull();
		assertThat(remove.roomId()).isEqualTo(100L);
		session.disconnect();
	}

	@Test
	void userRoomQueueDoesNotLeakTargetedEventsToAnotherAuthenticatedUser() throws Exception {
		StompSession targetSession = connect("access-token");
		StompSession otherSession = connect("other-access-token");
		BlockingQueue<ChatRoomListEvent> targetEvents = new LinkedBlockingQueue<>();
		BlockingQueue<ChatRoomListEvent> otherEvents = new LinkedBlockingQueue<>();
		targetSession.subscribe("/user/queue/rooms", frameHandler(ChatRoomListEvent.class, targetEvents));
		otherSession.subscribe("/user/queue/rooms", frameHandler(ChatRoomListEvent.class, otherEvents));
		awaitSubscriptionRegistration("42", "/user/queue/rooms");
		awaitSubscriptionRegistration("77", "/user/queue/rooms");

		roomListEventPublisher.publish(42L, ChatRoomListEvent.upsert(roomSummary(300L, false)));

		ChatRoomListEvent targetEvent = targetEvents.poll(3, TimeUnit.SECONDS);
		assertThat(targetEvent).isNotNull();
		assertThat(targetEvent.type()).isEqualTo("upsert");
		assertThat(targetEvent.room()).isNotNull();
		assertThat(targetEvent.room().roomId()).isEqualTo(300L);
		assertThat(otherEvents.poll(500, TimeUnit.MILLISECONDS)).isNull();
		targetSession.disconnect();
		otherSession.disconnect();
	}

	@SuppressWarnings("removal")
	private StompSession connect() throws Exception {
		return connect("access-token");
	}

	@SuppressWarnings("removal")
	private StompSession connect(String accessToken) throws Exception {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
		converter.getObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		client.setMessageConverter(converter);
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add(HttpHeaders.COOKIE, "access_token=" + accessToken);
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
		awaitSubscriptionRegistration("42", destination);
	}

	private void awaitSubscriptionRegistration(String userName, String destination) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) {
			if (hasSubscription(userName, destination)) {
				return;
			}
			Thread.sleep(50);
		}
		assertThat(hasSubscription(userName, destination)).isTrue();
	}

	private boolean hasSubscription(String userName, String destination) {
		var user = userRegistry.getUser(userName);
		if (user == null) {
			return false;
		}
		return user.getSessions().stream()
			.flatMap(session -> session.getSubscriptions().stream())
			.anyMatch(subscription -> destination.equals(subscription.getDestination()));
	}

	private ChatRoomSummaryResponse roomSummary(Long roomId, boolean pinned) {
		return new ChatRoomSummaryResponse(roomId, RoomType.direct, null, null, null, pinned, true, 0L, null);
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
		CanonicalAuthStateVerifier testCanonicalAuthStateVerifier() {
			return mock(CanonicalAuthStateVerifier.class);
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
