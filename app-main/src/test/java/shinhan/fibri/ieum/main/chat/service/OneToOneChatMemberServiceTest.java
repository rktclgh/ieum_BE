package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
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
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;

class OneToOneChatMemberServiceTest {

	private final ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final OneToOneChatMemberService service = new OneToOneChatMemberService(
		chatRoomRepository,
		chatMemberRepository,
		messageRepository
	);

	@Test
	void addInitialMembersSavesRequesterAndTarget() {
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		User requester = user(42L, "requester@example.com", "requester");
		User target = user(77L, "target@example.com", "target");

		service.addInitialMembers(room, requester, target);

		ArgumentCaptor<ChatMember> captor = ArgumentCaptor.forClass(ChatMember.class);
		verify(chatMemberRepository, org.mockito.Mockito.times(2)).save(captor.capture());
		assertThat(captor.getAllValues())
			.extracting(member -> member.getId().getUserId())
			.containsExactly(42L, 77L);
	}

	@Test
	void activateRequesterAdvancesOnlyLeftRequester() {
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		User requesterUser = user(42L, "requester@example.com", "requester");
		ChatMember requester = leftMember(room, requesterUser);
		when(chatMemberRepository.findByRoomIdAndUserIdForUpdate(100L, 42L))
			.thenReturn(Optional.of(requester));
		when(messageRepository.findMaxMessageIdByRoomId(100L)).thenReturn(55L);

		service.activateRequester(room, 42L);

		assertThat(requester.isActive()).isTrue();
		assertThat(requester.getVisibleAfterMessageId()).isEqualTo(55L);
	}

	@Test
	void activateRequesterDoesNotAdvanceActiveRequester() {
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		User requesterUser = user(42L, "requester@example.com", "requester");
		ChatMember requester = ChatMember.join(room, requesterUser);
		when(chatMemberRepository.findByRoomIdAndUserIdForUpdate(100L, 42L))
			.thenReturn(Optional.of(requester));

		service.activateRequester(room, 42L);

		verify(messageRepository, never()).findMaxMessageIdByRoomId(anyLong());
		assertThat(requester.getVisibleAfterMessageId()).isZero();
	}

	@Test
	void prepareSendActivatesOnlyLeftRecipientBeforeMessageInsert() {
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember sender = ChatMember.join(room, user(42L, "sender@example.com", "sender"));
		ChatMember recipient = leftMember(room, user(77L, "recipient@example.com", "recipient"));
		when(chatRoomRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.findByRoomIdForUpdateOrderByUserId(100L))
			.thenReturn(List.of(sender, recipient));
		when(messageRepository.findMaxMessageIdByRoomId(100L)).thenReturn(88L);

		ChatMember result = service.prepareSend(100L, 42L);

		assertThat(result).isSameAs(sender);
		assertThat(recipient.getVisibleAfterMessageId()).isEqualTo(88L);
		assertThat(recipient.isActive()).isTrue();
	}

	@Test
	void prepareSendDoesNotAdvanceActiveRecipient() {
		ChatRoom room = room(ChatRoom.question(9L, 42L, 77L), 100L);
		ChatMember sender = ChatMember.join(room, user(42L, "sender@example.com", "sender"));
		ChatMember recipient = leftMember(room, user(77L, "recipient@example.com", "recipient"));
		recipient.reactivateAfter(63L);
		when(chatRoomRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.findByRoomIdForUpdateOrderByUserId(100L))
			.thenReturn(List.of(sender, recipient));

		service.prepareSend(100L, 42L);

		verify(messageRepository, never()).findMaxMessageIdByRoomId(anyLong());
		assertThat(recipient.getVisibleAfterMessageId()).isEqualTo(63L);
	}

	@Test
	void prepareSendFailsClosedBeforeActivatingCorruptOneToOneMembership() {
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember sender = ChatMember.join(room, user(42L, "sender@example.com", "sender"));
		ChatMember firstRecipient = leftMember(room, user(77L, "first@example.com", "first"));
		ChatMember secondRecipient = leftMember(room, user(88L, "second@example.com", "second"));
		when(chatRoomRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.findByRoomIdForUpdateOrderByUserId(100L))
			.thenReturn(List.of(sender, firstRecipient, secondRecipient));

		assertThatThrownBy(() -> service.prepareSend(100L, 42L))
			.isInstanceOf(IllegalStateException.class);

		verify(messageRepository, never()).findMaxMessageIdByRoomId(anyLong());
		assertThat(firstRecipient.isActive()).isFalse();
		assertThat(secondRecipient.isActive()).isFalse();
	}

	private ChatMember leftMember(ChatRoom room, User user) {
		ChatMember member = ChatMember.join(room, user);
		member.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		return member;
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
