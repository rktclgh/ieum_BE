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
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.exception.BlockedChatException;
import shinhan.fibri.ieum.main.chat.exception.NotFriendsException;
import shinhan.fibri.ieum.main.chat.exception.SelfChatRoomException;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

class ChatServiceTest {

	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final FriendService friendService = org.mockito.Mockito.mock(FriendService.class);
	private final ChatService service = new ChatService(
		userRepository,
		chatRoomRepository,
		chatMemberRepository,
		messageRepository,
		friendService
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
