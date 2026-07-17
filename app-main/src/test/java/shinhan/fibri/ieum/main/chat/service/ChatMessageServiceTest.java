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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatReplyPreview;
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
	void responseAndWebSocketEventExposeMessageTypeForUserAndSystemMessages() {
		User sender = user(42L, "sender@example.com", "sender");
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-08T12:00:00+09:00");
		Message userMessage = Message.text(room, sender, "hello", createdAt);
		Message systemMessage = Message.system(room, sender, "sender left", createdAt);

		assertThat(ChatMessageResponse.from(userMessage).messageType()).isEqualTo(MessageType.user);
		assertThat(ChatMessageResponse.from(systemMessage).messageType()).isEqualTo(MessageType.system);
		assertThat(WsMessageEvent.from(systemMessage).messageType()).isEqualTo(MessageType.system);
	}

	@Test
	void sendPersistsVisibleUserReplyAndExposesOneLevelPreviewForRestAndWebSocket() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		ChatMember otherMember = ChatMember.join(room, friend);
		List<ChatMember> activeMembers = List.of(member, otherMember);
		Message target = message(400L, room, friend, "original message");
		ChatReplyPreview expectedPreview = new ChatReplyPreview(
			400L,
			77L,
			"friend",
			"original message",
			null
		);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(chatMemberRepository.findActiveUserIdsByRoomId(100L)).thenReturn(List.of(42L, 77L));
		when(chatMemberRepository.findActiveByRoomIdAndUserIds(100L, List.of(42L, 77L)))
			.thenReturn(activeMembers);
		when(messageRepository.findReplyTargetById(400L)).thenReturn(Optional.of(target));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});

		ChatMessageResponse response = service.send(
			principal(42L),
			100L,
			new SendChatMessageRequest("reply text", null, 400L)
		);

		assertThat(response.replyTo()).isEqualTo(expectedPreview);
		ArgumentCaptor<Message> savedMessage = ArgumentCaptor.forClass(Message.class);
		verify(messageRepository).save(savedMessage.capture());
		assertThat(savedMessage.getValue().getReplyTo()).isSameAs(target);
		ArgumentCaptor<WsMessageEvent> eventCaptor = ArgumentCaptor.forClass(WsMessageEvent.class);
		verify(roomEventPublisher).publishUserMessage(
			eventCaptor.capture(),
			org.mockito.ArgumentMatchers.same(activeMembers)
		);
		assertThat(eventCaptor.getValue().replyTo()).isEqualTo(expectedPreview);
		InOrder inOrder = inOrder(chatMemberRepository, messageRepository);
		inOrder.verify(chatMemberRepository).findActiveByRoomIdAndUserId(100L, 42L);
		inOrder.verify(messageRepository).findReplyTargetById(400L);
		inOrder.verify(messageRepository).save(any(Message.class));
	}

	@Test
	void sendPersistsImageReplyWithAnImageParentPreview() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		UUID parentImageFileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		Message target = Message.image(
			room,
			friend,
			parentImageFileId,
			OffsetDateTime.parse("2026-07-16T09:00:00+09:00")
		);
		setField(target, "id", 400L);
		UUID replyImageFileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.findReplyTargetById(400L)).thenReturn(Optional.of(target));
		when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
			Message message = invocation.getArgument(0);
			setField(message, "id", 501L);
			return message;
		});

		ChatMessageResponse response = service.send(
			principal(42L),
			100L,
			new SendChatMessageRequest(null, replyImageFileId, 400L)
		);

		assertThat(response.content()).isNull();
		assertThat(response.imageUrl()).isEqualTo("/api/v1/files/" + replyImageFileId);
		assertThat(response.replyTo()).isEqualTo(new ChatReplyPreview(
			400L,
			77L,
			"friend",
			null,
			"/api/v1/files/" + parentImageFileId
		));
		ArgumentCaptor<Message> savedMessage = ArgumentCaptor.forClass(Message.class);
		verify(messageRepository).save(savedMessage.capture());
		assertThat(savedMessage.getValue().getImageFileId()).isEqualTo(replyImageFileId);
		assertThat(savedMessage.getValue().getReplyTo()).isSameAs(target);
	}

	@Test
	void sendRejectsReplyTargetFromAnotherRoomBeforeAnySideEffect() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatRoom otherRoom = room(ChatRoom.group(9L), 101L);
		ChatMember member = ChatMember.join(room, me);
		Message target = message(400L, otherRoom, friend, "other room message");
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.findReplyTargetById(400L)).thenReturn(Optional.of(target));

		assertThatThrownBy(() -> service.send(
			principal(42L),
			100L,
			new SendChatMessageRequest("reply text", null, 400L)
		)).isInstanceOf(InvalidChatMessageException.class);

		verify(messageRepository, never()).save(any(Message.class));
		verify(chatMemberRepository, never()).restoreLeftMembersByRoomIdExceptSender(any(), any());
		verify(chatRoomListChangeEmitter, never()).upsert(any(), any());
	}

	@Test
	void sendRejectsSystemReplyTargetBeforeAnySideEffect() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		Message target = Message.system(room, friend, "member left", OffsetDateTime.parse("2026-07-16T10:00:00+09:00"));
		setField(target, "id", 400L);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.findReplyTargetById(400L)).thenReturn(Optional.of(target));

		assertThatThrownBy(() -> service.send(
			principal(42L),
			100L,
			new SendChatMessageRequest("reply text", null, 400L)
		)).isInstanceOf(InvalidChatMessageException.class);

		verify(messageRepository, never()).save(any(Message.class));
		verify(chatMemberRepository, never()).restoreLeftMembersByRoomIdExceptSender(any(), any());
	}

	@Test
	void sendRejectsDeletedReplyTargetBeforeAnySideEffect() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		Message target = message(400L, room, friend, "deleted message");
		target.markDeleted(OffsetDateTime.parse("2026-07-16T10:00:00+09:00"));
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.findReplyTargetById(400L)).thenReturn(Optional.of(target));

		assertThatThrownBy(() -> service.send(
			principal(42L),
			100L,
			new SendChatMessageRequest("reply text", null, 400L)
		)).isInstanceOf(InvalidChatMessageException.class);

		verify(messageRepository, never()).save(any(Message.class));
		verify(chatMemberRepository, never()).restoreLeftMembersByRoomIdExceptSender(any(), any());
	}

	@Test
	void sendRejectsReplyTargetHiddenBeforeRejoinBeforeAnySideEffect() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		member.hideHistoryThrough(400L);
		Message target = message(400L, room, friend, "hidden message");
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(messageRepository.findReplyTargetById(400L)).thenReturn(Optional.of(target));

		assertThatThrownBy(() -> service.send(
			principal(42L),
			100L,
			new SendChatMessageRequest("reply text", null, 400L)
		)).isInstanceOf(InvalidChatMessageException.class);

		verify(messageRepository, never()).save(any(Message.class));
		verify(chatMemberRepository, never()).restoreLeftMembersByRoomIdExceptSender(any(), any());
	}

	@Test
	void sendSavesMessageAndPublishesEventWhenNoTransactionSynchronization() {
		User me = user(42L, "me@example.com", "me");
		UUID profileFileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		me.linkProfileImage(profileFileId);
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
		assertThat(response.senderProfileImageUrl()).isEqualTo("/api/v1/files/" + profileFileId);
		assertThat(response.replyTo()).isNull();
		verify(chatMemberRepository).restoreLeftMembersByRoomIdExceptSender(100L, 42L);
		ArgumentCaptor<WsMessageEvent> eventCaptor = ArgumentCaptor.forClass(WsMessageEvent.class);
		verify(roomEventPublisher).publishUserMessage(eventCaptor.capture(), any());
		assertThat(eventCaptor.getValue().content()).isEqualTo("hello");
		assertThat(eventCaptor.getValue().senderProfileImageUrl()).isEqualTo("/api/v1/files/" + profileFileId);
		assertThat(eventCaptor.getValue().messageType()).isEqualTo(MessageType.user);
		assertThat(eventCaptor.getValue().replyTo()).isNull();
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
			verify(roomEventPublisher, never()).publishUserMessage(any(), any());
			verify(chatNotificationPublisher, never()).messageCreated(any());
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(roomEventPublisher).publishUserMessage(any(), any());
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

		verify(roomEventPublisher, never()).publishUserMessage(any(), any());
		verify(chatNotificationPublisher, never()).messageCreated(any());
	}

	@Test
	void webSocketFailureAfterCommitStillAttemptsChatPushAndDoesNotEscape() {
		prepareSuccessfulTextMessage();
		doThrow(new IllegalStateException("secret websocket detail"))
			.when(roomEventPublisher).publishUserMessage(any(), any());

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

		verify(roomEventPublisher).publishUserMessage(any(), any());
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

	private Message message(Long id, ChatRoom room, User sender, String content) {
		Message message = Message.text(room, sender, content, OffsetDateTime.parse("2026-07-16T09:00:00+09:00"));
		setField(message, "id", id);
		return message;
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
