package shinhan.fibri.ieum.main.chat.service;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomListEvent;

class SimpChatRoomListEventPublisherTest {

	private final SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
	private final SimpChatRoomListEventPublisher publisher = new SimpChatRoomListEventPublisher(messagingTemplate);

	@Test
	void publishesRoomListEventToUserRoomsQueue() {
		ChatRoomListEvent event = ChatRoomListEvent.remove(100L);

		publisher.publish(42L, event);

		verify(messagingTemplate).convertAndSendToUser("42", "/queue/rooms", event);
	}
}
