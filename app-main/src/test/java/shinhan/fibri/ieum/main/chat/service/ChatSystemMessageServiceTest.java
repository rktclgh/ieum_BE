package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;

class ChatSystemMessageServiceTest {

	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final ChatRoomListChangeEmitter chatRoomListChangeEmitter = org.mockito.Mockito.mock(ChatRoomListChangeEmitter.class);
	private final RoomEventPublisher roomEventPublisher = org.mockito.Mockito.mock(RoomEventPublisher.class);
	private final ChatNotificationPublisher chatNotificationPublisher = org.mockito.Mockito.mock(ChatNotificationPublisher.class);
	private final ChatSystemMessageService service = new ChatSystemMessageService(
		messageRepository,
		chatMemberRepository,
		chatRoomListChangeEmitter,
		roomEventPublisher
	);

	@Test
	void recordMeetingDeparturePersistsNeutralSystemMessageUpdatesRemainingRoomsAndPublishesImmediatelyWithoutSynchronization() {
		ChatRoom room = room(ChatRoom.group(7L), 9L);
		User departingUser = user(42L, "민지");
		OffsetDateTime leftAt = OffsetDateTime.parse("2026-07-16T12:00:00+09:00");
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> savedMessage(invocation.getArgument(0)));
		when(chatMemberRepository.findActiveUserIdsByRoomId(9L)).thenReturn(List.of(77L));

		service.recordMeetingDeparture(room, departingUser, leftAt);

		verify(messageRepository).save(argThat(message ->
			message.getMessageType() == MessageType.system
				&& message.getSender().getId().equals(42L)
				&& message.getContent().equals("민지님이 모임을 떠났습니다")
		));
		verify(chatRoomListChangeEmitter).upsert(9L, List.of(77L));
		verify(roomEventPublisher).publish(argThat(event ->
			event.messageType() == MessageType.system
				&& event.roomId().equals(9L)
				&& event.senderId().equals(42L)
				&& event.senderNickname().equals("민지")
				&& event.content().equals("민지님이 모임을 떠났습니다")
		));
		verifyNoInteractions(chatNotificationPublisher);
	}

	@Test
	void recordMeetingDepartureDefersRoomFanoutUntilAfterCommit() {
		ChatRoom room = room(ChatRoom.group(7L), 9L);
		User departingUser = user(42L, "민지");
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> savedMessage(invocation.getArgument(0)));
		when(chatMemberRepository.findActiveUserIdsByRoomId(9L)).thenReturn(List.of(77L));

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.recordMeetingDeparture(room, departingUser, OffsetDateTime.parse("2026-07-16T12:00:00+09:00"));

			verify(roomEventPublisher, never()).publish(any());
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(roomEventPublisher).publish(argThat(event ->
			event.messageType() == MessageType.system
				&& event.roomId().equals(9L)
				&& event.senderId().equals(42L)
				&& event.senderNickname().equals("민지")
		));
		verifyNoInteractions(chatNotificationPublisher);
	}

	@Test
	void recordMeetingDepartureDoesNotPublishRoomFanoutWhenTransactionRollsBack() {
		ChatRoom room = room(ChatRoom.group(7L), 9L);
		User departingUser = user(42L, "민지");
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> savedMessage(invocation.getArgument(0)));
		when(chatMemberRepository.findActiveUserIdsByRoomId(9L)).thenReturn(List.of(77L));

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.recordMeetingDeparture(room, departingUser, OffsetDateTime.parse("2026-07-16T12:00:00+09:00"));
			TransactionSynchronizationManager.getSynchronizations()
				.forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(roomEventPublisher, never()).publish(any());
		verifyNoInteractions(chatNotificationPublisher);
	}

	@Test
	void recordMeetingDepartureSkipsRoomListUpsertWhenNobodyRemains() {
		ChatRoom room = room(ChatRoom.group(7L), 9L);
		User departingUser = user(42L, "민지");
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> savedMessage(invocation.getArgument(0)));
		when(chatMemberRepository.findActiveUserIdsByRoomId(9L)).thenReturn(List.of());

		service.recordMeetingDeparture(room, departingUser, OffsetDateTime.parse("2026-07-16T12:00:00+09:00"));

		verify(chatRoomListChangeEmitter, never()).upsert(any(), any());
		verify(roomEventPublisher).publish(any());
	}

	@Test
	void recordMeetingDepartureSwallowsRoomFanoutFailureAfterCommit() {
		ChatRoom room = room(ChatRoom.group(7L), 9L);
		User departingUser = user(42L, "민지");
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> savedMessage(invocation.getArgument(0)));
		when(chatMemberRepository.findActiveUserIdsByRoomId(9L)).thenReturn(List.of(77L));
		doThrow(new IllegalStateException("websocket unavailable")).when(roomEventPublisher).publish(any());

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.recordMeetingDeparture(room, departingUser, OffsetDateTime.parse("2026-07-16T12:00:00+09:00"));

			assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(roomEventPublisher).publish(any());
		verifyNoInteractions(chatNotificationPublisher);
	}

	private Message savedMessage(Message message) {
		setField(message, "id", 501L);
		return message;
	}

	private User user(Long id, String nickname) {
		User user = User.createEmailUser(
			nickname + "@example.com",
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
		setField(user, "id", id);
		return user;
	}

	private ChatRoom room(ChatRoom room, Long id) {
		setField(room, "id", id);
		return room;
	}

	private void setField(Object target, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
