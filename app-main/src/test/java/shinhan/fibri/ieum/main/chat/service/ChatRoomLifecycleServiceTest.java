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
	private final ChatRoomListChangeEmitter chatRoomListChangeEmitter = org.mockito.Mockito.mock(ChatRoomListChangeEmitter.class);
	private final ChatRoomLifecycleService service = new ChatRoomLifecycleService(
		userRepository,
		chatRoomRepository,
		chatMemberRepository,
		chatRoomListChangeEmitter
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
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L));
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
	void getOrCreateQuestionRoomReturnsExistingRoomAndRestoresMembers() {
		User first = user(42L, "first@example.com", "first");
		User second = user(77L, "second@example.com", "second");
		ChatRoom room = room(ChatRoom.question(9L, 42L, 77L), 100L);
		ChatMember firstMember = ChatMember.join(room, first);
		firstMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(first));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(second));
		when(chatRoomRepository.findByRoomKey("q:9:42:77")).thenReturn(Optional.of(room));
		when(chatMemberRepository.findByRoom_Id(100L)).thenReturn(List.of(firstMember));

		Long roomId = service.getOrCreateQuestionRoom(9L, 42L, 77L);

		assertThat(roomId).isEqualTo(100L);
		assertThat(firstMember.getLeftAt()).isNull();
		verify(chatMemberRepository).save(any(ChatMember.class));
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L, 77L));
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
		when(chatRoomRepository.findByRoomKey("q:9:42:77")).thenReturn(Optional.empty());
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class)))
			.thenThrow(new DataIntegrityViolationException("uidx_chat_rooms_room_key"));

		assertThatThrownBy(() -> service.getOrCreateQuestionRoom(9L, 42L, 77L))
			.isInstanceOf(DataIntegrityViolationException.class);

		verify(chatRoomRepository).saveAndFlush(any(ChatRoom.class));
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
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L));
	}

	@Test
	void removeMemberMarksMemberLeft() {
		User user = user(42L, "member@example.com", "member");
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		ChatMember member = ChatMember.join(room, user);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		service.removeMember(100L, 42L);

		assertThat(member.getLeftAt()).isNotNull();
		verify(chatRoomListChangeEmitter).remove(100L, List.of(42L));
	}

	@Test
	void removeMemberDoesNotEmitWhenActiveMemberIsMissing() {
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.removeMember(100L, 42L))
			.isInstanceOf(shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException.class);

		verify(chatRoomListChangeEmitter, never()).remove(any(), any());
	}

	@Test
	void disbandGroupRoomDeletesRoomAndNotifiesAllActiveMembers() {
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		when(chatRoomRepository.findByMeetingId(7L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.findActiveUserIdsByRoomId(100L)).thenReturn(List.of(42L, 77L));

		service.disbandGroupRoom(7L);

		verify(chatRoomRepository).delete(room);
		verify(chatRoomListChangeEmitter).remove(100L, List.of(42L, 77L));
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
