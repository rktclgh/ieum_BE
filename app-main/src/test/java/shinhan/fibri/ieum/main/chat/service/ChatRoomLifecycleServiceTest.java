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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;

class ChatRoomLifecycleServiceTest {

	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final OneToOneChatMemberService oneToOneChatMemberService =
		org.mockito.Mockito.mock(OneToOneChatMemberService.class);
	private final ChatRoomLifecycleService service = new ChatRoomLifecycleService(
		userRepository,
		chatRoomRepository,
		chatMemberRepository,
		oneToOneChatMemberService
	);

	@Test
	void createGroupRoomCreatesRoomAndHostMember() {
		User host = user(42L, "host@example.com", "host");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(host));
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class))).thenAnswer(invocation -> {
			ChatRoom room = invocation.getArgument(0);
			setField(room, "id", 100L);
			return room;
		});

		Long roomId = service.createGroupRoom(7L, 42L);

		assertThat(roomId).isEqualTo(100L);
		verify(chatMemberRepository).save(any(ChatMember.class));
	}

	@Test
	void createGroupRoomStillJoinsTheCallingTransaction() throws NoSuchMethodException {
		var method = ChatRoomLifecycleService.class.getDeclaredMethod(
			"createGroupRoom",
			Long.class,
			Long.class
		);
		var transactional = method.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
	}

	@Test
	void createGroupRoomDoesNotRetryWhenMeetingIdRaceOccurs() {
		User host = user(42L, "host@example.com", "host");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(host));
		when(chatRoomRepository.findByMeetingId(7L)).thenReturn(Optional.empty());
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class)))
			.thenThrow(new DataIntegrityViolationException("uidx_chat_rooms_meeting_id"));

		assertThatThrownBy(() -> service.createGroupRoom(7L, 42L))
			.isInstanceOf(DataIntegrityViolationException.class);
		verify(chatRoomRepository).saveAndFlush(any(ChatRoom.class));
	}

	@Test
	void getOrCreateQuestionRoomCreatesRoomAndInitialMembers() {
		User first = user(42L, "first@example.com", "first");
		User second = user(77L, "second@example.com", "second");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(first));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(second));
		when(chatRoomRepository.findByRoomKeyForUpdate("q:9:42:77")).thenReturn(Optional.empty());
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class))).thenAnswer(invocation -> {
			ChatRoom room = invocation.getArgument(0);
			setField(room, "id", 100L);
			return room;
		});

		Long roomId = service.getOrCreateQuestionRoom(9L, 42L, 77L);

		assertThat(roomId).isEqualTo(100L);
		verify(oneToOneChatMemberService).addInitialMembers(any(ChatRoom.class),
			org.mockito.Mockito.same(first), org.mockito.Mockito.same(second));
	}

	@Test
	void getOrCreateQuestionRoomActivatesOnlyRequesterInExistingRoom() {
		User requester = user(42L, "requester@example.com", "requester");
		User target = user(77L, "target@example.com", "target");
		ChatRoom room = room(ChatRoom.question(9L, 42L, 77L), 100L);
		ChatMember targetMember = ChatMember.join(room, target);
		targetMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(requester));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(target));
		when(chatRoomRepository.findByRoomKeyForUpdate("q:9:42:77")).thenReturn(Optional.of(room));

		Long roomId = service.getOrCreateQuestionRoom(9L, 42L, 77L);

		assertThat(roomId).isEqualTo(100L);
		verify(oneToOneChatMemberService).activateRequester(room, 42L);
		verify(oneToOneChatMemberService, never()).activateRequester(room, 77L);
		assertThat(targetMember.isActive()).isFalse();
		verify(chatMemberRepository, never()).findByRoom_Id(100L);
	}

	@Test
	void getOrCreateQuestionRoomJoinsTheCallingTransaction() throws NoSuchMethodException {
		var method = ChatRoomLifecycleService.class.getDeclaredMethod(
			"getOrCreateQuestionRoom",
			Long.class,
			Long.class,
			Long.class
		);
		var transactional = method.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
	}

	@Test
	void getOrCreateQuestionRoomLeavesRoomKeyRaceRecoveryToTheCaller() {
		User first = user(42L, "first@example.com", "first");
		User second = user(77L, "second@example.com", "second");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(first));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(second));
		when(chatRoomRepository.findByRoomKeyForUpdate("q:9:42:77")).thenReturn(Optional.empty());
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class)))
			.thenThrow(new DataIntegrityViolationException("uidx_chat_rooms_room_key"));

		assertThatThrownBy(() -> service.getOrCreateQuestionRoom(9L, 42L, 77L))
			.isInstanceOf(DataIntegrityViolationException.class);

		verify(chatRoomRepository).saveAndFlush(any(ChatRoom.class));
	}

	@Test
	void getOrCreateDirectRoomCreatesRoomAndInitialMembers() {
		User requester = user(42L, "requester@example.com", "requester");
		User target = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(requester));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(target));
		when(chatRoomRepository.findByRoomKeyForUpdate("d:42:77")).thenReturn(Optional.empty());
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class))).thenAnswer(invocation -> {
			ChatRoom room = invocation.getArgument(0);
			setField(room, "id", 100L);
			return room;
		});

		Long roomId = service.getOrCreateDirectRoom(42L, 77L);

		assertThat(roomId).isEqualTo(100L);
		verify(oneToOneChatMemberService).addInitialMembers(any(ChatRoom.class),
			org.mockito.Mockito.same(requester), org.mockito.Mockito.same(target));
	}

	@Test
	void getOrCreateDirectRoomActivatesOnlyRequesterInExistingRoom() {
		User requester = user(42L, "requester@example.com", "requester");
		User target = user(77L, "target@example.com", "target");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember targetMember = ChatMember.join(room, target);
		targetMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(requester));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(target));
		when(chatRoomRepository.findByRoomKeyForUpdate("d:42:77")).thenReturn(Optional.of(room));

		Long roomId = service.getOrCreateDirectRoom(42L, 77L);

		assertThat(roomId).isEqualTo(100L);
		verify(oneToOneChatMemberService).activateRequester(room, 42L);
		verify(oneToOneChatMemberService, never()).activateRequester(room, 77L);
		assertThat(targetMember.isActive()).isFalse();
	}

	@Test
	void addMemberRejoinsExistingMember() {
		User user = user(42L, "member@example.com", "member");
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		ChatMember member = ChatMember.join(room, user);
		member.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(chatMemberRepository.findByRoom_Id(100L)).thenReturn(List.of(member));

		service.addMember(100L, 42L);

		assertThat(member.getLeftAt()).isNull();
	}

	@Test
	void removeMemberMarksMemberLeft() {
		User user = user(42L, "member@example.com", "member");
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		ChatMember member = ChatMember.join(room, user);
		when(chatMemberRepository.findByRoomIdAndUserIdForUpdate(100L, 42L)).thenReturn(Optional.of(member));

		service.removeMember(100L, 42L);

		assertThat(member.getLeftAt()).isNotNull();
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
