package shinhan.fibri.ieum.main.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimpRoomEventPublisher implements RoomEventPublisher {

	private final SimpMessagingTemplate messagingTemplate;

	@Override
	public void publish(WsMessageEvent event) {
		messagingTemplate.convertAndSend("/topic/rooms/%d".formatted(event.roomId()), event);
	}
}
