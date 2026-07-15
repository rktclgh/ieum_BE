package shinhan.fibri.ieum.main.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomListEvent;

@Component
@RequiredArgsConstructor
public class SimpChatRoomListEventPublisher implements ChatRoomListEventPublisher {

	private final SimpMessagingTemplate messagingTemplate;

	@Override
	public void publish(Long userId, ChatRoomListEvent event) {
		messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/rooms", event);
	}
}
