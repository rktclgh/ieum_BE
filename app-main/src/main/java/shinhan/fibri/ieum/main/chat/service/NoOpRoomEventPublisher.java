package shinhan.fibri.ieum.main.chat.service;

import org.springframework.stereotype.Component;

@Component
public class NoOpRoomEventPublisher implements RoomEventPublisher {

	@Override
	public void publish(WsMessageEvent event) {
	}
}
