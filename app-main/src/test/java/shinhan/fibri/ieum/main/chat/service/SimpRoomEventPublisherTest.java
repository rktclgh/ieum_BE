package shinhan.fibri.ieum.main.chat.service;

import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class SimpRoomEventPublisherTest {

	private final SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
	private final SimpRoomEventPublisher publisher = new SimpRoomEventPublisher(messagingTemplate);

	@Test
	void publishesRoomEventToRoomTopic() {
		WsMessageEvent event = new WsMessageEvent(
			501L,
			100L,
			42L,
			"sender",
			"hello",
			null,
			OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
		);

		publisher.publish(event);

		verify(messagingTemplate).convertAndSend("/topic/rooms/100", event);
	}
}
