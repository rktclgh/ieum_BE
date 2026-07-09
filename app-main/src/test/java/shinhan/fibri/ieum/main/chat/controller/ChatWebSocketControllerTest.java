package shinhan.fibri.ieum.main.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.chat.dto.SendChatMessageRequest;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.InvalidChatMessageException;
import shinhan.fibri.ieum.main.chat.exception.InvalidChatSessionException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.service.ChatMessageService;
import shinhan.fibri.ieum.main.chat.websocket.ChatWebSocketErrorResponse;
import shinhan.fibri.ieum.main.chat.websocket.ChatWebSocketPrincipal;

class ChatWebSocketControllerTest {

	private final ChatMessageService chatMessageService = org.mockito.Mockito.mock(ChatMessageService.class);
	private final ChatWebSocketController controller = new ChatWebSocketController(chatMessageService);

	@Test
	void sendDelegatesToChatMessageServiceWithWebSocketPrincipal() {
		ChatWebSocketPrincipal principal = principal();
		SendChatMessageRequest request = new SendChatMessageRequest("hello", null);

		controller.send(principal, 100L, request);

		verify(chatMessageService).send(principal.authenticatedUser(), 100L, request);
	}

	@Test
	void handleInvalidMessageReturnsValidationFailedError() {
		ChatWebSocketErrorResponse response = controller.handleInvalidMessage(
			new InvalidChatMessageException("content or imageFileId is required")
		);

		assertThat(response).isEqualTo(new ChatWebSocketErrorResponse(
			"VALIDATION_FAILED",
			"content or imageFileId is required",
			null
		));
	}

	@Test
	void handleInvalidSessionReturnsInvalidSessionError() {
		ChatWebSocketErrorResponse response = controller.handleInvalidSession(
			new InvalidChatSessionException("Unauthenticated chat session")
		);

		assertThat(response).isEqualTo(new ChatWebSocketErrorResponse(
			"INVALID_SESSION",
			"Unauthenticated chat session",
			null
		));
	}

	@Test
	void handleNotRoomMemberReturnsNotRoomMemberError() {
		ChatWebSocketErrorResponse response = controller.handleNotRoomMember(new NotRoomMemberException());

		assertThat(response.code()).isEqualTo("NOT_ROOM_MEMBER");
	}

	@Test
	void handleRoomClosedReturnsRoomClosedError() {
		ChatWebSocketErrorResponse response = controller.handleRoomClosed(new ChatRoomNotFoundException());

		assertThat(response.code()).isEqualTo("ROOM_CLOSED");
	}

	private ChatWebSocketPrincipal principal() {
		return new ChatWebSocketPrincipal(
			new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active),
			"sid-1"
		);
	}
}
