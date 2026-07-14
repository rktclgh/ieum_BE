package shinhan.fibri.ieum.main.chat.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimpChatWebSocketErrorSender implements ChatWebSocketErrorSender {

	private final SimpMessagingTemplate messagingTemplate;

	@Override
	public void send(ChatWebSocketPrincipal principal, ChatWebSocketErrorResponse error) {
		messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", error);
	}
}
