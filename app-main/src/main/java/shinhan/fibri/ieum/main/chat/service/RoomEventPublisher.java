package shinhan.fibri.ieum.main.chat.service;

public interface RoomEventPublisher {

	void publish(WsMessageEvent event);
}
