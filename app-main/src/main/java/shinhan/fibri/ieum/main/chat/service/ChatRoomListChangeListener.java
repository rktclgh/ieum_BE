package shinhan.fibri.ieum.main.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomListEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRoomListChangeListener {

	private final ChatRoomSummaryQueryService summaryQueryService;
	private final ChatRoomListEventPublisher publisher;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(ChatRoomListChangeEvent event) {
		try {
			if (event.type() == ChatRoomListChangeEvent.Type.REMOVE) {
				publishRemove(event);
				return;
			}
			publishUpsert(event);
		} catch (RuntimeException exception) {
			log.warn("Failed to publish chat room list change event. roomId={}, type={}", event.roomId(), event.type(), exception);
		}
	}

	private void publishUpsert(ChatRoomListChangeEvent event) {
		summaryQueryService.findActiveForRoomAndUsers(event.roomId(), event.userIds())
			.forEach((userId, summary) -> publishSafely(userId, ChatRoomListEvent.upsert(summary), event));
	}

	private void publishRemove(ChatRoomListChangeEvent event) {
		event.userIds()
			.forEach(userId -> publishSafely(userId, ChatRoomListEvent.remove(event.roomId()), event));
	}

	private void publishSafely(Long userId, ChatRoomListEvent roomListEvent, ChatRoomListChangeEvent sourceEvent) {
		try {
			publisher.publish(userId, roomListEvent);
		} catch (RuntimeException exception) {
			log.warn(
				"Failed to publish chat room list change to user. roomId={}, type={}, userId={}",
				sourceEvent.roomId(),
				sourceEvent.type(),
				userId,
				exception
			);
		}
	}
}
