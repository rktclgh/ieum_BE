package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;

class ChatRoomLifecycleServiceTest {

	private final List<TransactionDefinition> transactionDefinitions = new java.util.ArrayList<>();
	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			transactionDefinitions.add(definition);
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
		}

		@Override
		public void rollback(TransactionStatus status) {
		}
	};
	private final ChatRoomLifecycleService service = new ChatRoomLifecycleService(
		userRepository,
		chatRoomRepository,
		chatMemberRepository,
		transactionManager
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
	void createGroupRoomDoesNotUseRequiresNewTransaction() {
		User host = user(42L, "host@example.com", "host");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(host));
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class))).thenAnswer(invocation -> {
			ChatRoom room = invocation.getArgument(0);
			setField(room, "id", 100L);
			return room;
		});

		Long roomId = service.createGroupRoom(7L, 42L);

		assertThat(roomId).isEqualTo(100L);
		assertThat(transactionDefinitions)
			.noneMatch(definition -> definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
	}

	@Test
	void getOrCreateQuestionRoomRetriesAndRestoresMembersWhenRoomKeyRaceOccurs() {
		User first = user(42L, "first@example.com", "first");
		User second = user(77L, "second@example.com", "second");
		ChatRoom room = room(ChatRoom.question(9L, 42L, 77L), 100L);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(first));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(second));
		when(chatRoomRepository.findByRoomKey("q:9:42:77"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(room));
		when(chatRoomRepository.saveAndFlush(any(ChatRoom.class)))
			.thenThrow(new DataIntegrityViolationException("uidx_chat_rooms_room_key"));
		when(chatMemberRepository.findByRoom_Id(100L)).thenReturn(List.of());

		Long roomId = service.getOrCreateQuestionRoom(9L, 42L, 77L);

		assertThat(roomId).isEqualTo(100L);
		verify(chatRoomRepository).saveAndFlush(any(ChatRoom.class));
		verify(chatMemberRepository, org.mockito.Mockito.times(2)).save(any(ChatMember.class));
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
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

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
