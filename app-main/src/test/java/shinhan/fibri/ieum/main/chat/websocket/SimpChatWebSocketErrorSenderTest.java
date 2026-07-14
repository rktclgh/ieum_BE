package shinhan.fibri.ieum.main.chat.websocket;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

class SimpChatWebSocketErrorSenderTest {

	private final SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
	private final SimpChatWebSocketErrorSender sender = new SimpChatWebSocketErrorSender(messagingTemplate);

	@Test
	void sendPublishesErrorToUserQueue() {
		ChatWebSocketPrincipal principal = new ChatWebSocketPrincipal(
			new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active),
			"sid-1"
		);
		ChatWebSocketErrorResponse error = new ChatWebSocketErrorResponse("RATE_LIMITED", "Too many chat messages", 100L);

		sender.send(principal, error);

		verify(messagingTemplate).convertAndSendToUser("42", "/queue/errors", error);
	}
}
