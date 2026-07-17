package shinhan.fibri.ieum.main.chat.service;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;

@Service
@RequiredArgsConstructor
public class ChatSystemMessageService {

	private static final Logger log = LoggerFactory.getLogger(ChatSystemMessageService.class);

	private final MessageRepository messageRepository;
	private final ChatMemberRepository chatMemberRepository;
	private final ChatRoomListChangeEmitter chatRoomListChangeEmitter;
	private final RoomEventPublisher roomEventPublisher;

	public void recordMeetingDeparture(ChatRoom room, User departingUser, OffsetDateTime leftAt) {
		Message message = messageRepository.save(Message.system(
			room,
			departingUser,
			"%s님이 모임을 떠났습니다".formatted(departingUser.getNickname()),
			leftAt
		));
		List<Long> activeUserIds = chatMemberRepository.findActiveUserIdsByRoomId(room.getId());
		if (!activeUserIds.isEmpty()) {
			chatRoomListChangeEmitter.upsert(room.getId(), activeUserIds);
		}
		publishAfterCommit(WsMessageEvent.from(message));
	}

	private void publishAfterCommit(WsMessageEvent event) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			publish(event);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publish(event);
			}
		});
	}

	private void publish(WsMessageEvent event) {
		try {
			roomEventPublisher.publish(event);
		} catch (RuntimeException exception) {
			log.warn(
				"event=chat_fanout_failed channel=websocket roomId={} messageId={} failureType={}",
				event.roomId(),
				event.messageId(),
				exception.getClass().getSimpleName()
			);
		}
	}
}
