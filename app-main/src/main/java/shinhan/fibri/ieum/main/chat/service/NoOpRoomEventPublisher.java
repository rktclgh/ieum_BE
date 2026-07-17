package shinhan.fibri.ieum.main.chat.service;

import java.util.List;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;

public class NoOpRoomEventPublisher implements RoomEventPublisher {

	@Override
	public void publish(WsMessageEvent event) {
	}

	@Override
	public void publishUserMessage(WsMessageEvent event, List<ChatMember> recipients) {
	}
}
