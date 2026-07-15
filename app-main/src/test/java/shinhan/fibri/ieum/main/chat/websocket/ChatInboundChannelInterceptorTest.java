package shinhan.fibri.ieum.main.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.CanonicalAuthStateVerifier;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.chat.service.ChatMessageRateLimiter;

class ChatInboundChannelInterceptorTest {

	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final RedisAuthSessionStore sessionStore = org.mockito.Mockito.mock(RedisAuthSessionStore.class);
	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final CanonicalAuthStateVerifier canonicalAuthStateVerifier = new CanonicalAuthStateVerifier(userRepository);
	private final ChatMessageRateLimiter rateLimiter = org.mockito.Mockito.mock(ChatMessageRateLimiter.class);
	private final ChatWebSocketErrorSender errorSender = org.mockito.Mockito.mock(ChatWebSocketErrorSender.class);
	private final ChatInboundChannelInterceptor interceptor = new ChatInboundChannelInterceptor(
		chatMemberRepository,
		sessionStore,
		canonicalAuthStateVerifier,
		rateLimiter,
		errorSender
	);

	@Test
	void connectSetsUserFromHandshakePrincipal() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
		accessor.setSessionAttributes(Map.of(ChatWebSocketPrincipal.ATTRIBUTE_NAME, principal));

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(StompHeaderAccessor.wrap(result).getUser()).isEqualTo(principal);
	}

	@Test
	void subscribeAllowsActiveRoomMember() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SUBSCRIBE, principal);
		accessor.setDestination("/topic/rooms/100");
		stubValidSession(principal);
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNotNull();
		InOrder order = inOrder(sessionStore, userRepository, chatMemberRepository);
		order.verify(sessionStore).findBySessionId("sid-1");
		order.verify(userRepository).findAuthStateById(42L);
		order.verify(chatMemberRepository).existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L);
	}

	@Test
	void subscribeRejectsNonMemberAndSendsError() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SUBSCRIBE, principal);
		accessor.setDestination("/topic/rooms/100");
		stubValidSession(principal);
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(false);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"NOT_ROOM_MEMBER",
			"Room membership is required",
			100L
		));
	}

	@Test
	void sendRejectsInvalidSessionBeforeRateLimit() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SEND, principal);
		accessor.setDestination("/app/rooms/100/send");
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.empty());

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"INVALID_SESSION",
			"Chat session is invalid",
			100L
		));
		verify(rateLimiter, never()).tryConsumeSend(42L);
	}

	@Test
	void sendRejectsWhenRateLimitIsExceeded() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SEND, principal);
		accessor.setDestination("/app/rooms/100/send");
		stubValidSession(principal);
		when(rateLimiter.tryConsumeSend(42L)).thenReturn(false);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"RATE_LIMITED",
			"Too many chat messages",
			100L
		));
	}

	@Test
	void sendRejectsNonMemberAfterSessionAndRateLimitPass() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SEND, principal);
		accessor.setDestination("/app/rooms/100/send");
		stubValidSession(principal);
		when(rateLimiter.tryConsumeSend(42L)).thenReturn(true);
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(false);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"NOT_ROOM_MEMBER",
			"Room membership is required",
			100L
		));
	}

	@Test
	void sendAllowsWhenSessionRateLimitAndMembershipAreValid() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SEND, principal);
		accessor.setDestination("/app/rooms/100/send");
		stubValidSession(principal);
		when(rateLimiter.tryConsumeSend(42L)).thenReturn(true);
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNotNull();
		InOrder order = inOrder(sessionStore, userRepository, rateLimiter, chatMemberRepository);
		order.verify(sessionStore).findBySessionId("sid-1");
		order.verify(userRepository).findAuthStateById(42L);
		order.verify(rateLimiter).tryConsumeSend(42L);
		order.verify(chatMemberRepository).existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L);
	}

	@Test
	void subscribeRejectsWildcardDestinationWithoutMembershipCheck() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SUBSCRIBE, principal);
		accessor.setDestination("/topic/rooms/*");
		stubValidSession(principal);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"NOT_ROOM_MEMBER",
			"Room subscription is not allowed",
			null
		));
		verify(chatMemberRepository, never()).existsByRoom_IdAndUser_IdAndLeftAtIsNull(any(), any());
	}

	@Test
	void subscribeRejectsOversizedRoomIdWithoutThrowing() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SUBSCRIBE, principal);
		accessor.setDestination("/topic/rooms/99999999999999999999");
		stubValidSession(principal);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"NOT_ROOM_MEMBER",
			"Room subscription is not allowed",
			null
		));
		verify(chatMemberRepository, never()).existsByRoom_IdAndUser_IdAndLeftAtIsNull(any(), any());
	}

	@Test
	void subscribeAllowsOwnUserErrorQueue() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SUBSCRIBE, principal);
		accessor.setDestination("/user/queue/errors");
		stubValidSession(principal);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNotNull();
	}

	@Test
	void subscribeRejectsNonErrorUserQueueDestinations() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SUBSCRIBE, principal);
		accessor.setDestination("/user/queue/notifications");
		stubValidSession(principal);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"NOT_ROOM_MEMBER",
			"Room subscription is not allowed",
			null
		));
	}

	@Test
	void sendRejectsDirectBrokerDestinationAfterSessionCheckAndBeforeRateLimit() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SEND, principal);
		accessor.setDestination("/topic/rooms/100");
		stubValidSession(principal);

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"VALIDATION_FAILED",
			"Unsupported send destination",
			null
		));
		verify(sessionStore).findBySessionId("sid-1");
		verify(userRepository).findAuthStateById(42L);
		verify(rateLimiter, never()).tryConsumeSend(any());
	}

	@ParameterizedTest(name = "stale subscribe is rejected: {0}")
	@MethodSource("staleCanonicalStates")
	void subscribeRejectsCanonicalMismatchBeforeMembership(
		String ignored,
		UserAuthState staleCanonicalState
	) {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		AuthSession authSession = session("sid-1", 42L, UserStatus.active);
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SUBSCRIBE, principal);
		accessor.setDestination("/topic/rooms/100");
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(authSession));
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.of(staleCanonicalState));

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"INVALID_SESSION",
			"Chat session is invalid",
			100L
		));
		verify(chatMemberRepository, never()).existsByRoom_IdAndUser_IdAndLeftAtIsNull(any(), any());
		verify(rateLimiter, never()).tryConsumeSend(any());
	}

	@Test
	void subscribeRejectsStaleSessionForOwnErrorQueue() {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		AuthSession authSession = session("sid-1", 42L, UserStatus.active);
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SUBSCRIBE, principal);
		accessor.setDestination("/user/queue/errors");
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(authSession));
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.of(new UserAuthState(
			"user42@example.com",
			UserRole.user,
			UserStatus.active,
			1L
		)));

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"INVALID_SESSION",
			"Chat session is invalid",
			null
		));
		verify(chatMemberRepository, never()).existsByRoom_IdAndUser_IdAndLeftAtIsNull(any(), any());
		verify(rateLimiter, never()).tryConsumeSend(any());
	}

	@ParameterizedTest(name = "stale send is rejected: {0}")
	@MethodSource("staleCanonicalStates")
	void sendRejectsCanonicalMismatchBeforeRateLimitAndMembership(
		String ignored,
		UserAuthState staleCanonicalState
	) {
		ChatWebSocketPrincipal principal = principal(42L, "sid-1");
		AuthSession authSession = session("sid-1", 42L, UserStatus.active);
		StompHeaderAccessor accessor = authenticatedAccessor(StompCommand.SEND, principal);
		accessor.setDestination("/app/rooms/100/send");
		when(sessionStore.findBySessionId("sid-1")).thenReturn(Optional.of(authSession));
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.of(staleCanonicalState));

		Message<?> result = interceptor.preSend(message(accessor), null);

		assertThat(result).isNull();
		verify(errorSender).send(principal, new ChatWebSocketErrorResponse(
			"INVALID_SESSION",
			"Chat session is invalid",
			100L
		));
		verify(rateLimiter, never()).tryConsumeSend(any());
		verify(chatMemberRepository, never()).existsByRoom_IdAndUser_IdAndLeftAtIsNull(any(), any());
	}

	private static Stream<Arguments> staleCanonicalStates() {
		return Stream.of(
			Arguments.of("auth version changed", new UserAuthState(
				"user42@example.com",
				UserRole.user,
				UserStatus.active,
				1L
			)),
			Arguments.of("role changed", new UserAuthState(
				"user42@example.com",
				UserRole.admin,
				UserStatus.active,
				0L
			)),
			Arguments.of("status changed", new UserAuthState(
				"user42@example.com",
				UserRole.user,
				UserStatus.suspended,
				0L
			))
		);
	}

	private StompHeaderAccessor authenticatedAccessor(StompCommand command, ChatWebSocketPrincipal principal) {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
		accessor.setUser(principal);
		return accessor;
	}

	private Message<byte[]> message(StompHeaderAccessor accessor) {
		return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
	}

	private ChatWebSocketPrincipal principal(Long userId, String sessionId) {
		return new ChatWebSocketPrincipal(
			new AuthenticatedUser(userId, "user%d@example.com".formatted(userId), UserRole.user, UserStatus.active),
			sessionId
		);
	}

	private void stubValidSession(ChatWebSocketPrincipal principal) {
		Long userId = principal.authenticatedUser().userId();
		AuthSession authSession = session(principal.sessionId(), userId, UserStatus.active);
		when(sessionStore.findBySessionId(principal.sessionId())).thenReturn(Optional.of(authSession));
		when(userRepository.findAuthStateById(userId)).thenReturn(Optional.of(new UserAuthState(
			authSession.email(),
			authSession.role(),
			authSession.status(),
			authSession.authVersion()
		)));
	}

	private AuthSession session(String sessionId, Long userId, UserStatus status) {
		return new AuthSession(
			sessionId,
			userId,
			"user%d@example.com".formatted(userId),
			"refresh-hash",
			null,
			UserRole.user,
			status,
			OffsetDateTime.parse("2026-07-08T00:00:00+09:00"),
			0L
		);
	}
}
