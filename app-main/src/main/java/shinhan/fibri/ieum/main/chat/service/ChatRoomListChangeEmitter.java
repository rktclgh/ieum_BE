package shinhan.fibri.ieum.main.chat.service;

import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatRoomListChangeEmitter {

	private final ApplicationEventPublisher eventPublisher;

	public void upsert(Long roomId, Collection<Long> userIds) {
		eventPublisher.publishEvent(ChatRoomListChangeEvent.upsert(roomId, userIds));
	}

	public void remove(Long roomId, Collection<Long> userIds) {
		eventPublisher.publishEvent(ChatRoomListChangeEvent.remove(roomId, userIds));
	}
}
