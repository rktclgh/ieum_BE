package shinhan.fibri.ieum.main.chat.service;

public class NoOpRoomEventPublisher implements RoomEventPublisher {

	@Override
	public void publish(WsMessageEvent event) {
	}
}
