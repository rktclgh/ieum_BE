package shinhan.fibri.ieum.main.chat.websocket;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.auth.session.CanonicalAuthStateVerifier;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.chat.service.ChatMessageRateLimiter;

@Component
public class ChatInboundChannelInterceptor implements ChannelInterceptor {
	private static final Logger log = LoggerFactory.getLogger(ChatInboundChannelInterceptor.class);

	private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/rooms/(\\d+)$");
	private static final Pattern USER_ROOM_MESSAGE_QUEUE_PATTERN = Pattern.compile("^/user/queue/rooms/(\\d+)$");
	private static final Pattern SEND_DESTINATION_PATTERN = Pattern.compile("^/app/rooms/(\\d+)/send$");
	private static final String USER_ERROR_QUEUE_DESTINATION = "/user/queue/errors";
	private static final String USER_ROOM_LIST_QUEUE_DESTINATION = "/user/queue/rooms";

	private final ChatMemberRepository chatMemberRepository;
	private final RedisAuthSessionStore sessionStore;
	private final CanonicalAuthStateVerifier canonicalAuthStateVerifier;
	private final ChatMessageRateLimiter rateLimiter;
	private final ChatWebSocketErrorSender errorSender;

	public ChatInboundChannelInterceptor(
		ChatMemberRepository chatMemberRepository,
		RedisAuthSessionStore sessionStore,
		CanonicalAuthStateVerifier canonicalAuthStateVerifier,
		ChatMessageRateLimiter rateLimiter,
		@Lazy ChatWebSocketErrorSender errorSender
	) {
		this.chatMemberRepository = chatMemberRepository;
		this.sessionStore = sessionStore;
		this.canonicalAuthStateVerifier = canonicalAuthStateVerifier;
		this.rateLimiter = rateLimiter;
		this.errorSender = errorSender;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
		StompCommand command = accessor.getCommand();
		if (command == StompCommand.CONNECT) {
			return handleConnect(message, accessor);
		}
		if (command == StompCommand.SUBSCRIBE) {
			return handleSubscribe(message, accessor);
		}
		if (command == StompCommand.SEND) {
			return handleSend(message, accessor);
		}
		return message;
	}

	private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
		ChatWebSocketPrincipal principal = handshakePrincipal(accessor).orElse(null);
		if (principal == null) {
			return null;
		}
		accessor.setUser(principal);
		return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
	}

	private Message<?> handleSubscribe(Message<?> message, StompHeaderAccessor accessor) {
		ChatWebSocketPrincipal principal = currentPrincipal(accessor).orElse(null);
		String destination = accessor.getDestination();
		if (principal == null || destination == null) {
			return null;
		}
		Long topicRoomId = parseRoomId(destination, ROOM_TOPIC_PATTERN).orElse(null);
		Long userRoomMessageQueueRoomId = parseRoomId(destination, USER_ROOM_MESSAGE_QUEUE_PATTERN).orElse(null);
		Long roomId = topicRoomId != null ? topicRoomId : userRoomMessageQueueRoomId;
		if (!hasActiveSession(principal)) {
			sendError(principal, "INVALID_SESSION", "Chat session is invalid", roomId);
			return null;
		}
		// 오류·room-list 개인 큐는 Spring이 세션 소유자로 스코프한다. room message 개인 큐는
		// 아래에서 정확한 room id와 active membership을 추가로 검증한다.
		if (USER_ERROR_QUEUE_DESTINATION.equals(destination)
			|| USER_ROOM_LIST_QUEUE_DESTINATION.equals(destination)) {
			return message;
		}
		// 그 외 목적지는 default-deny — room topic과 개인 room queue 모두 정확한 room id 및 active membership이 필요하다.
		// 와일드카드(/topic/rooms/*, /user/queue/rooms/*) 구독으로 전체 방을 도청하는 우회를 차단한다.
		if (roomId == null) {
			sendError(principal, "NOT_ROOM_MEMBER", "Room subscription is not allowed", null);
			return null;
		}
		if (!isActiveRoomMember(roomId, principal)) {
			sendError(principal, "NOT_ROOM_MEMBER", "Room membership is required", roomId);
			return null;
		}
		return message;
	}

	private Message<?> handleSend(Message<?> message, StompHeaderAccessor accessor) {
		ChatWebSocketPrincipal principal = currentPrincipal(accessor).orElse(null);
		if (principal == null) {
			return null;
		}
		Long roomId = parseRoomId(accessor.getDestination(), SEND_DESTINATION_PATTERN).orElse(null);
		if (!hasActiveSession(principal)) {
			sendError(principal, "INVALID_SESSION", "Chat session is invalid", roomId);
			return null;
		}
		// SEND는 정확히 /app/rooms/{id}/send 만 허용(default-deny).
		// 브로커 목적지(/topic/rooms/{id})로 직접 SEND해 컨트롤러·검증을 우회한 위조 브로드캐스트를 차단한다.
		if (roomId == null) {
			sendError(principal, "VALIDATION_FAILED", "Unsupported send destination", null);
			return null;
		}
		if (!rateLimiter.tryConsumeSend(principal.authenticatedUser().userId())) {
			sendError(principal, "RATE_LIMITED", "Too many chat messages", roomId);
			return null;
		}
		if (!isActiveRoomMember(roomId, principal)) {
			sendError(principal, "NOT_ROOM_MEMBER", "Room membership is required", roomId);
			return null;
		}
		return message;
	}

	private Optional<ChatWebSocketPrincipal> handshakePrincipal(StompHeaderAccessor accessor) {
		if (accessor.getSessionAttributes() == null) {
			return Optional.empty();
		}
		Object principal = accessor.getSessionAttributes().get(ChatWebSocketPrincipal.ATTRIBUTE_NAME);
		if (principal instanceof ChatWebSocketPrincipal chatPrincipal) {
			return Optional.of(chatPrincipal);
		}
		return Optional.empty();
	}

	private Optional<ChatWebSocketPrincipal> currentPrincipal(StompHeaderAccessor accessor) {
		if (accessor.getUser() instanceof ChatWebSocketPrincipal principal) {
			return Optional.of(principal);
		}
		return Optional.empty();
	}

	private Optional<Long> parseRoomId(String destination, Pattern pattern) {
		if (destination == null) {
			return Optional.empty();
		}
		Matcher matcher = pattern.matcher(destination);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		try {
			return Optional.of(Long.valueOf(matcher.group(1)));
		} catch (NumberFormatException e) {
			// 20자리 이상 등 Long 범위를 넘는 방 ID는 잘못된 목적지로 취급(연결 종료 대신 에러 응답).
			return Optional.empty();
		}
	}

	private boolean hasActiveSession(ChatWebSocketPrincipal principal) {
		try {
			return sessionStore.findBySessionId(principal.sessionId())
				.filter(session -> session.userId().equals(principal.authenticatedUser().userId()))
				.flatMap(canonicalAuthStateVerifier::findActiveMatching)
				.isPresent();
		} catch (RuntimeException exception) {
			log.warn(
				"WebSocket session authorization revalidation failed; rejecting frame: userId={} cause={}",
				principal.authenticatedUser().userId(),
				exception.getClass().getSimpleName()
			);
			return false;
		}
	}

	private boolean isActiveRoomMember(Long roomId, ChatWebSocketPrincipal principal) {
		return chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(
			roomId,
			principal.authenticatedUser().userId()
		);
	}

	private void sendError(ChatWebSocketPrincipal principal, String code, String message, Long roomId) {
		try {
			errorSender.send(principal, new ChatWebSocketErrorResponse(code, message, roomId));
		} catch (RuntimeException exception) {
			log.warn(
				"WebSocket error response delivery failed: userId={} cause={}",
				principal.authenticatedUser().userId(),
				exception.getClass().getSimpleName()
			);
		}
	}
}
