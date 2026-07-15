package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.dto.SendChatMessageRequest;
import shinhan.fibri.ieum.main.chat.exception.InvalidChatMessageException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;

class ChatMessageServiceTest {

	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final RoomEventPublisher roomEventPublisher = org.mockito.Mockito.mock(RoomEventPublisher.class);
	private final ChatNotificationPublisher chatNotificationPublisher = org.mockito.Mockito.mock(ChatNotificationPublisher.class);
	private final ChatRoomListChangeEmitter chatRoomListChangeEmitter = org.mockito.Mockito.mock(ChatRoomListChangeEmitter.class);
	private final ChatMessageService service = new ChatMessageService(
		chatMemberRepository,
		messageRepository,
		roomEventPublisher,
		chatNotificationPublisher,
		chatRoomListChangeEmitter
	);

	@Test
	void sendSavesMessageAndPublishesEventWhenNoTransactionSynchronization() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});

		var response = service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));

		assertThat(response.messageId()).isEqualTo(501L);
		verify(chatMemberRepository).restoreLeftMembersByRoomIdExceptSender(100L, 42L);
		ArgumentCaptor<WsMessageEvent> eventCaptor = ArgumentCaptor.forClass(WsMessageEvent.class);
		verify(roomEventPublisher).publish(eventCaptor.capture());
		assertThat(eventCaptor.getValue().content()).isEqualTo("hello");
		ArgumentCaptor<ChatPushTrigger> triggerCaptor = ArgumentCaptor.forClass(ChatPushTrigger.class);
		verify(chatNotificationPublisher).messageCreated(triggerCaptor.capture());
		assertThat(triggerCaptor.getValue()).isEqualTo(new ChatPushTrigger(501L, 100L, 42L));
	}

	@Test
	void sendRestoresLeftMembersForQuestionRoom() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.question(9L, 42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(chatMemberRepository.findActiveUserIdsByRoomId(100L)).thenReturn(List.of(42L, 77L));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});

		service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));

		InOrder inOrder = inOrder(chatMemberRepository, messageRepository, chatRoomListChangeEmitter);
		inOrder.verify(chatMemberRepository).restoreLeftMembersByRoomIdExceptSender(100L, 42L);
		inOrder.verify(messageRepository).save(any(Message.class));
		inOrder.verify(chatMemberRepository).findActiveUserIdsByRoomId(100L);
		inOrder.verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L, 77L));
	}

	@Test
	void sendRecordsRoomListUpsertForActiveMembersAfterDirectRoomRestoration() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(chatMemberRepository.findActiveUserIdsByRoomId(100L)).thenReturn(List.of(42L, 77L));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});

		service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));

		InOrder inOrder = inOrder(chatMemberRepository, messageRepository, chatRoomListChangeEmitter);
		inOrder.verify(chatMemberRepository).findActiveByRoomIdAndUserId(100L, 42L);
		inOrder.verify(chatMemberRepository).restoreLeftMembersByRoomIdExceptSender(100L, 42L);
		inOrder.verify(messageRepository).save(any(Message.class));
		inOrder.verify(chatMemberRepository).findActiveUserIdsByRoomId(100L);
		inOrder.verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L, 77L));
	}

	@Test
	void sendDoesNotRestoreLeftMembersForGroupRoom() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});

		service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));

		verify(chatMemberRepository, never()).restoreLeftMembersByRoomIdExceptSender(100L, 42L);
		assertThat(member.getRoom().getRoomType()).isEqualTo(RoomType.group);
	}

	@Test
	void sendRecordsRoomListUpsertForCurrentActiveGroupMembersOnly() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(chatMemberRepository.findActiveUserIdsByRoomId(100L)).thenReturn(List.of(42L, 88L));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});

		service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));

		verify(chatMemberRepository, never()).restoreLeftMembersByRoomIdExceptSender(100L, 42L);
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L, 88L));
	}

	@Test
	void sendPublishesAfterCommitWhenTransactionSynchronizationIsActive() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));
			verify(roomEventPublisher, never()).publish(any());
			verify(chatNotificationPublisher, never()).messageCreated(any());
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(roomEventPublisher).publish(any());
		verify(chatNotificationPublisher).messageCreated(new ChatPushTrigger(501L, 100L, 42L));
	}

	@Test
	void rollbackPublishesNeitherWebSocketNorChatPush() {
		prepareSuccessfulTextMessage();

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));
			TransactionSynchronizationManager.getSynchronizations()
				.forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(roomEventPublisher, never()).publish(any());
		verify(chatNotificationPublisher, never()).messageCreated(any());
	}

	@Test
	void webSocketFailureAfterCommitStillAttemptsChatPushAndDoesNotEscape() {
		prepareSuccessfulTextMessage();
		doThrow(new IllegalStateException("secret websocket detail")).when(roomEventPublisher).publish(any());

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));
			assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(chatNotificationPublisher).messageCreated(new ChatPushTrigger(501L, 100L, 42L));
	}

	@Test
	void chatPushFailureAfterCommitDoesNotEscapeAfterWebSocketAttempt() {
		prepareSuccessfulTextMessage();
		doThrow(new IllegalStateException("secret push detail")).when(chatNotificationPublisher).messageCreated(any());

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null));
			assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(roomEventPublisher).publish(any());
	}

	@Test
	void sendRejectsBlankContentWhenImageIsMissing() {
		assertThatThrownBy(() -> service.send(principal(42L), 100L, new SendChatMessageRequest(" ", null)))
			.isInstanceOf(InvalidChatMessageException.class);
		verify(chatRoomListChangeEmitter, never()).upsert(any(), any());
	}

	@Test
	void sendRejectsWhenBothContentAndImageAreProvided() {
		assertThatThrownBy(() -> service.send(
			principal(42L),
			100L,
			new SendChatMessageRequest("caption", java.util.UUID.randomUUID())
		))
			.isInstanceOf(InvalidChatMessageException.class);
		verify(messageRepository, never()).save(any());
		verify(chatRoomListChangeEmitter, never()).upsert(any(), any());
	}

	@Test
	void sendRejectsTooLongContent() {
		String content = "a".repeat(2001);

		assertThatThrownBy(() -> service.send(principal(42L), 100L, new SendChatMessageRequest(content, null)))
			.isInstanceOf(InvalidChatMessageException.class);
		verify(chatRoomListChangeEmitter, never()).upsert(any(), any());
	}

	@Test
	void sendRequiresActiveMembership() {
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.send(principal(42L), 100L, new SendChatMessageRequest("hello", null)))
			.isInstanceOf(NotRoomMemberException.class);
		verify(chatRoomListChangeEmitter, never()).upsert(any(), any());
	}

	private void prepareSuccessfulTextMessage() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});
	}

	private AuthenticatedUser principal(Long userId) {
		return new AuthenticatedUser(userId, "user" + userId + "@example.com", UserRole.user, UserStatus.active);
	}

	private User user(Long id, String email, String nickname) {
		User user = User.createEmailUser(
			email,
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
