package shinhan.fibri.ieum.main.chat.controller;

import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import shinhan.fibri.ieum.main.chat.dto.SendChatMessageRequest;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.InvalidChatMessageException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.service.ChatMessageService;
import shinhan.fibri.ieum.main.chat.websocket.ChatWebSocketErrorResponse;
import shinhan.fibri.ieum.main.chat.websocket.ChatWebSocketPrincipal;

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

	private final ChatMessageService chatMessageService;

	@MessageMapping("/rooms/{roomId}/send")
	public void send(
		Principal principal,
		@DestinationVariable Long roomId,
		@Payload SendChatMessageRequest request
	) {
		ChatWebSocketPrincipal chatPrincipal = (ChatWebSocketPrincipal) principal;
		chatMessageService.send(chatPrincipal.authenticatedUser(), roomId, request);
	}

	@MessageExceptionHandler(InvalidChatMessageException.class)
	@SendToUser("/queue/errors")
	public ChatWebSocketErrorResponse handleInvalidMessage(InvalidChatMessageException exception) {
		return new ChatWebSocketErrorResponse("VALIDATION_FAILED", exception.getMessage(), null);
	}

	@MessageExceptionHandler(NotRoomMemberException.class)
	@SendToUser("/queue/errors")
	public ChatWebSocketErrorResponse handleNotRoomMember(NotRoomMemberException exception) {
		return new ChatWebSocketErrorResponse("NOT_ROOM_MEMBER", exception.getMessage(), null);
	}

	@MessageExceptionHandler(ChatRoomNotFoundException.class)
	@SendToUser("/queue/errors")
	public ChatWebSocketErrorResponse handleRoomClosed(ChatRoomNotFoundException exception) {
		return new ChatWebSocketErrorResponse("ROOM_CLOSED", exception.getMessage(), null);
	}
}
