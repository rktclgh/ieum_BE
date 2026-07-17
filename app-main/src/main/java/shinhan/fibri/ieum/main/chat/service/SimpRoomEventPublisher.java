package shinhan.fibri.ieum.main.chat.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;

@Component
@RequiredArgsConstructor
public class SimpRoomEventPublisher implements RoomEventPublisher {

	private final SimpMessagingTemplate messagingTemplate;

	@Override
	public void publish(WsMessageEvent event) {
		messagingTemplate.convertAndSend("/topic/rooms/%d".formatted(event.roomId()), event);
	}

	@Override
	public void publishUserMessage(WsMessageEvent event, List<ChatMember> recipients) {
		recipients.forEach(member -> messagingTemplate.convertAndSendToUser(
			String.valueOf(member.getUser().getId()),
			"/queue/rooms/%d".formatted(event.roomId()),
			event.withReplyToVisibleAfter(member.getVisibleAfterMessageId())
		));
	}
}
