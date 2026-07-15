package shinhan.fibri.ieum.main.chat.service;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomListEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRoomListChangeListener {

	private final ChatRoomSummaryQueryService summaryQueryService;
	private final ChatRoomListEventPublisher publisher;
	private final PlatformTransactionManager transactionManager;
	private final Lock[] roomPublishLocks = createRoomPublishLocks();

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Async
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
		executeForRoom(event.roomId(), () -> {
			var summaries = executeInNewTransaction(() ->
				summaryQueryService.findActiveForRoomAndUsers(event.roomId(), event.userIds())
			);
			summaries.forEach((userId, summary) ->
				publishSafely(userId, ChatRoomListEvent.upsert(summary), event)
			);
		});
	}

	private void publishRemove(ChatRoomListChangeEvent event) {
		executeForRoom(event.roomId(), () ->
			event.userIds()
				.forEach(userId -> publishSafely(userId, ChatRoomListEvent.remove(event.roomId()), event))
		);
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

	private void executeForRoom(Long roomId, Runnable action) {
		Lock lock = roomPublishLocks[Math.floorMod(roomId.hashCode(), roomPublishLocks.length)];
		lock.lock();
		try {
			action.run();
		} finally {
			lock.unlock();
		}
	}

	private <T> T executeInNewTransaction(Supplier<T> action) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return transaction.execute(status -> action.get());
	}

	private Lock[] createRoomPublishLocks() {
		Lock[] locks = new Lock[64];
		for (int index = 0; index < locks.length; index++) {
			locks[index] = new ReentrantLock();
		}
		return locks;
	}
}
