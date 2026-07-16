package shinhan.fibri.ieum.main.chat.service;

import java.util.List;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;

public interface RoomEventPublisher {

	void publish(WsMessageEvent event);

	void publishUserMessage(WsMessageEvent event, List<ChatMember> recipients);
}
