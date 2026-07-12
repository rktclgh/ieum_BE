package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.exception.BlockedChatException;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.NotFriendsException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.exception.SelfChatRoomException;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

class ChatServiceTest {

	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final FriendService friendService = org.mockito.Mockito.mock(FriendService.class);
	private final MeetingRepository meetingRepository = org.mockito.Mockito.mock(MeetingRepository.class);
	private final PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
		}

		@Override
		public void rollback(TransactionStatus status) {
		}
	};
	private final ChatService service = new ChatService(
		userRepository,
		chatRoomRepository,
		chatMemberRepository,
		messageRepository,
		friendService,
		meetingRepository,
		transactionManager
	);

	@Test
	void createDirectRoomCreatesRoomAndTwoMembersWhenFriendshipIsAccepted() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(friend));
		when(friendService.areFriends(42L, 77L)).thenReturn(true);
		when(friendService.hasBlockBetween(42L, 77L)).thenReturn(false);
		when(chatRoomRepository.findByRoomKey("d:42:77")).thenReturn(Optional.empty());
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class))).thenAnswer(invocation -> {
			ChatRoom room = invocation.getArgument(0);
			setField(room, "id", 100L);
			return room;
		});

		var response = service.createDirectRoom(principal(42L), 77L);

		assertThat(response.roomId()).isEqualTo(100L);
		assertThat(response.roomType()).isEqualTo(RoomType.direct);
		ArgumentCaptor<ChatMember> memberCaptor = ArgumentCaptor.forClass(ChatMember.class);
		verify(chatMemberRepository, org.mockito.Mockito.times(2)).save(memberCaptor.capture());
		assertThat(memberCaptor.getAllValues())
			.extracting(member -> member.getUser().getId())
			.containsExactlyInAnyOrder(42L, 77L);
	}

	@Test
	void createDirectRoomRejoinsExistingRoomMembers() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = ChatRoom.direct(42L, 77L);
		setField(room, "id", 100L);
		ChatMember meMember = ChatMember.join(room, me);
		ChatMember friendMember = ChatMember.join(room, friend);
		meMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		friendMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(friend));
		when(friendService.areFriends(42L, 77L)).thenReturn(true);
		when(friendService.hasBlockBetween(42L, 77L)).thenReturn(false);
		when(chatRoomRepository.findByRoomKey("d:42:77")).thenReturn(Optional.of(room));
		when(chatMemberRepository.findByRoom_Id(100L)).thenReturn(List.of(meMember, friendMember));

		var response = service.createDirectRoom(principal(42L), 77L);

		assertThat(response.roomId()).isEqualTo(100L);
		assertThat(meMember.getLeftAt()).isNull();
		assertThat(friendMember.getLeftAt()).isNull();
		verify(chatRoomRepository, never()).saveAndFlush(any(ChatRoom.class));
	}

	@Test
	void createDirectRoomRetriesAndRestoresMembersWhenRoomKeyRaceOccurs() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember meMember = ChatMember.join(room, me);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(friend));
		when(friendService.areFriends(42L, 77L)).thenReturn(true);
		when(friendService.hasBlockBetween(42L, 77L)).thenReturn(false);
		when(chatRoomRepository.findByRoomKey("d:42:77"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(room));
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class)))
			.thenThrow(new DataIntegrityViolationException("uidx_chat_rooms_room_key"));
		when(chatMemberRepository.findByRoom_Id(100L)).thenReturn(List.of(meMember));

		var response = service.createDirectRoom(principal(42L), 77L);

		assertThat(response.roomId()).isEqualTo(100L);
		verify(chatRoomRepository).saveAndFlush(any(ChatRoom.class));
		verify(chatMemberRepository).save(any(ChatMember.class));
	}

	@Test
	void createDirectRoomRejectsSelf() {
		User me = user(42L, "me@example.com", "me");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));

		assertThatThrownBy(() -> service.createDirectRoom(principal(42L), 42L))
			.isInstanceOf(SelfChatRoomException.class);
	}

	@Test
	void createDirectRoomRejectsWhenTargetIsMissingOrDeleted() {
		User me = user(42L, "me@example.com", "me");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createDirectRoom(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void createDirectRoomRequiresAcceptedFriendship() {
		User me = user(42L, "me@example.com", "me");
		User target = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(target));
		when(friendService.areFriends(42L, 77L)).thenReturn(false);

		assertThatThrownBy(() -> service.createDirectRoom(principal(42L), 77L))
			.isInstanceOf(NotFriendsException.class);
	}

	@Test
	void createDirectRoomRejectsBlockedPair() {
		User me = user(42L, "me@example.com", "me");
		User target = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(target));
		when(friendService.areFriends(42L, 77L)).thenReturn(true);
		when(friendService.hasBlockBetween(42L, 77L)).thenReturn(true);

		assertThatThrownBy(() -> service.createDirectRoom(principal(42L), 77L))
			.isInstanceOf(BlockedChatException.class);
	}

	@Test
	void listRoomsCombinesMembershipUnreadAndLastMessageThenSortsPinnedFirst() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom normalRoom = room(ChatRoom.direct(42L, 77L), 100L);
		ChatRoom pinnedRoom = room(ChatRoom.question(10L, 42L, 88L), 200L);
		ChatMember normalMember = ChatMember.join(normalRoom, me);
		ChatMember pinnedMember = ChatMember.join(pinnedRoom, me);
		pinnedMember.setPinned(true, OffsetDateTime.parse("2026-07-08T12:00:00+09:00"));
		Message normalLast = message(501L, normalRoom, friend, "normal", "2026-07-08T11:00:00+09:00");
		Message pinnedLast = message(502L, pinnedRoom, me, "pinned", "2026-07-08T10:00:00+09:00");
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(normalRoom, pinnedRoom));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L, 200L)))
			.thenReturn(List.of(normalMember, pinnedMember));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L, 200L)))
			.thenReturn(List.of(unread(100L, 3L)));
		when(messageRepository.findLastMessagesByRoomIds(List.of(100L, 200L)))
			.thenReturn(List.of(normalLast, pinnedLast));

		var response = service.listRooms(principal(42L), null);

		assertThat(response)
			.extracting(room -> room.roomId())
			.containsExactly(200L, 100L);
		assertThat(response.get(0).pinned()).isTrue();
		assertThat(response.get(0).lastMessage().content()).isEqualTo("pinned");
		assertThat(response.get(1).unreadCount()).isEqualTo(3L);
	}

	@Test
	void getRoomRequiresActiveMembershipAndReturnsMembers() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember meMember = ChatMember.join(room, me);
		ChatMember friendMember = ChatMember.join(room, friend);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(meMember));
		when(chatMemberRepository.findByRoom_Id(100L)).thenReturn(List.of(meMember, friendMember));

		var response = service.getRoom(principal(42L), 100L);

		assertThat(response.roomId()).isEqualTo(100L);
		assertThat(response.members())
			.extracting(member -> member.userId())
			.containsExactlyInAnyOrder(42L, 77L);
	}

	@Test
	void getRoomThrowsWhenRoomDoesNotExist() {
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getRoom(principal(42L), 100L))
			.isInstanceOf(ChatRoomNotFoundException.class);
	}

	@Test
	void getRoomThrowsWhenPrincipalIsNotActiveMember() {
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getRoom(principal(42L), 100L))
			.isInstanceOf(NotRoomMemberException.class);
	}

	@Test
	void listMessagesUsesLookaheadAndNextCursor() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember meMember = ChatMember.join(room, me);
		Message newest = message(503L, room, friend, "newest", "2026-07-08T12:00:00+09:00");
		Message next = message(502L, room, me, "next", "2026-07-08T11:00:00+09:00");
		Message lookahead = message(501L, room, friend, "lookahead", "2026-07-08T10:00:00+09:00");
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(meMember));
		when(messageRepository.findLatestMessagesByRoomId(org.mockito.Mockito.eq(100L), any()))
			.thenReturn(List.of(newest, next, lookahead));

		var response = service.listMessages(principal(42L), 100L, null, 2);

		assertThat(response.items())
			.extracting(message -> message.messageId())
			.containsExactly(503L, 502L);
		assertThat(ChatMessageCursor.decode(response.nextCursor()).messageId()).isEqualTo(502L);
	}

	@Test
	void listMessagesRequiresActiveMembership() {
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.listMessages(principal(42L), 100L, null, 50))
			.isInstanceOf(NotRoomMemberException.class);
	}

	@Test
	void markRoomReadUpdatesLastReadAt() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		service.markRead(principal(42L), 100L);

		assertThat(member.getLastReadAt()).isNotNull();
	}

	@Test
	void setPinnedUpdatesPinnedAt() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		service.setPinned(principal(42L), 100L, true);
		assertThat(member.getPinnedAt()).isNotNull();

		service.setPinned(principal(42L), 100L, false);
		assertThat(member.getPinnedAt()).isNull();
	}

	@Test
	void setNotifyEnabledUpdatesNotifyFlag() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		service.setNotifyEnabled(principal(42L), 100L, false);

		assertThat(member.isNotifyEnabled()).isFalse();
	}

	@Test
	void leaveRoomSetsLeftAt() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		service.leaveRoom(principal(42L), 100L);

		assertThat(member.getLeftAt()).isNotNull();
	}

	@Test
	void leaveRoomRejectsGroupRoomBecauseMeetingLeaveIsCanonical() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		assertThatThrownBy(() -> service.leaveRoom(principal(42L), 100L))
			.hasMessage("Leave group chat via meeting leave API");
		assertThat(member.getLeftAt()).isNull();
	}

	@Test
	void disbandGroupRoomDeletesRoomWhenPrincipalIsMeetingHost() {
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);
		when(meetingRepository.existsByIdAndHostIdAndDeletedAtIsNull(7L, 42L)).thenReturn(true);

		service.disbandRoom(principal(42L), 100L);

		verify(chatRoomRepository).delete(room);
		verify(chatMemberRepository, never()).findActiveByRoomIdAndUserId(100L, 42L);
	}

	@Test
	void disbandGroupRoomRejectsNonHost() {
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);
		when(meetingRepository.existsByIdAndHostIdAndDeletedAtIsNull(7L, 42L)).thenReturn(false);

		assertThatThrownBy(() -> service.disbandRoom(principal(42L), 100L))
			.isInstanceOf(NotHostException.class);

		verify(chatRoomRepository, never()).delete(any(ChatRoom.class));
	}

	@Test
	void disbandRoomRejectsNonGroupRoom() {
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);

		assertThatThrownBy(() -> service.disbandRoom(principal(42L), 100L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Only group chat rooms can be disbanded");

		verify(chatRoomRepository, never()).delete(any(ChatRoom.class));
	}

	@Test
	void disbandRoomRequiresActiveMembership() {
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(false);

		assertThatThrownBy(() -> service.disbandRoom(principal(42L), 100L))
			.isInstanceOf(NotRoomMemberException.class);

		verify(chatRoomRepository, never()).delete(any(ChatRoom.class));
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

	private Message message(Long id, ChatRoom room, User sender, String content, String createdAt) {
		Message message = Message.text(room, sender, content, OffsetDateTime.parse(createdAt));
		setField(message, "id", id);
		return message;
	}

	private MessageRepository.RoomUnreadCount unread(Long roomId, Long count) {
		return new MessageRepository.RoomUnreadCount() {
			@Override
			public Long getRoomId() {
				return roomId;
			}

			@Override
			public Long getUnreadCount() {
				return count;
			}
		};
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
