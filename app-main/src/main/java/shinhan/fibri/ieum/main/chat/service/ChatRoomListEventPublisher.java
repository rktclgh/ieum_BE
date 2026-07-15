package shinhan.fibri.ieum.main.chat.service;

import shinhan.fibri.ieum.main.chat.dto.ChatRoomListEvent;

public interface ChatRoomListEventPublisher {

	void publish(Long userId, ChatRoomListEvent event);
}
