package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatNotice;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatNoticeRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.main.chat.exception.ChatNoticeNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.ChatNoticeSourceNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;

class ChatNoticeServiceTest {

	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final ChatNoticeRepository chatNoticeRepository = org.mockito.Mockito.mock(ChatNoticeRepository.class);
	private final ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
	private final ChatNoticeService service = new ChatNoticeService(
		chatMemberRepository,
		chatNoticeRepository,
		chatRoomRepository
	);

	@Test
	void registerAllowsAllRoomTypesForActiveMembersAndDerivesSourceMessage() {
		for (RoomType roomType : RoomType.values()) {
			User me = user(42L);
			User sender = user(77L);
			ChatRoom room = roomOf(roomType, 100L + roomType.ordinal());
			ChatMember member = ChatMember.join(room, me);
			Message source = message(500L + roomType.ordinal(), room, sender, "공지 대상");
			ChatNotice notice = notice(900L + roomType.ordinal(), room, source, me);
			when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));
			when(chatMemberRepository.findActiveByRoomIdAndUserId(room.getId(), 42L)).thenReturn(Optional.of(member));
			when(chatNoticeRepository.findVisibleSourceMessage(room.getId(), source.getId(), 42L))
				.thenReturn(Optional.of(source));
			when(chatNoticeRepository.insertIgnore(room.getId(), source.getId(), 42L)).thenReturn(Optional.of(notice.getId()));
			when(chatNoticeRepository.findVisibleByIdAndRoomId(notice.getId(), room.getId(), 42L))
				.thenReturn(Optional.of(notice));

			var result = service.registerNotice(principal(42L), room.getId(), source.getId());

			assertThat(result.created()).isTrue();
			assertThat(result.notice().noticeId()).isEqualTo(notice.getId());
			assertThat(result.notice().message().content()).isEqualTo("공지 대상");
			assertThat(result.notice().pinned()).isFalse();
		}
	}

	@Test
	void duplicateRegistrationReturnsCanonicalExistingNotice() {
		User me = user(42L);
		User sender = user(77L);
		ChatRoom room = roomOf(RoomType.direct, 100L);
		ChatMember member = ChatMember.join(room, me);
		Message source = message(501L, room, sender, "already");
		ChatNotice existing = notice(901L, room, source, me);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(chatNoticeRepository.findVisibleSourceMessage(100L, 501L, 42L)).thenReturn(Optional.of(source));
		when(chatNoticeRepository.insertIgnore(100L, 501L, 42L)).thenReturn(Optional.empty());
		when(chatNoticeRepository.findVisibleByRoomIdAndMessageId(100L, 501L, 42L)).thenReturn(Optional.of(existing));

		var result = service.registerNotice(principal(42L), 100L, 501L);

		assertThat(result.created()).isFalse();
		assertThat(result.notice().noticeId()).isEqualTo(901L);
	}

	@Test
	void registerRejectsNonMemberOrInvisibleSourceWithoutWriting() {
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(ChatRoom.direct(42L, 77L)));
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.registerNotice(principal(42L), 100L, 501L))
			.isInstanceOf(NotRoomMemberException.class);

		User me = user(42L);
		ChatRoom room = roomOf(RoomType.direct, 100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(ChatMember.join(room, me)));
		when(chatNoticeRepository.findVisibleSourceMessage(100L, 501L, 42L)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.registerNotice(principal(42L), 100L, 501L))
			.isInstanceOf(ChatNoticeSourceNotFoundException.class);

		verify(chatNoticeRepository, never()).insertIgnore(any(), any(), any());
	}

	@Test
	void listUsesCursorAndIncludesPinnedNoticeOutsidePage() {
		User me = user(42L);
		User sender = user(77L);
		ChatRoom room = roomOf(RoomType.direct, 100L);
		room.pinNotice(999L);
		ChatMember member = ChatMember.join(room, me);
		ChatNotice newest = notice(903L, room, message(503L, room, sender, "new"), me);
		ChatNotice next = notice(902L, room, message(502L, room, sender, "next"), me);
		ChatNotice lookahead = notice(901L, room, message(501L, room, sender, "lookahead"), me);
		ChatNotice pinned = notice(999L, room, message(490L, room, sender, "pinned"), me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatNoticeRepository.findLatestVisible(100L, 42L, Pageable.ofSize(3)))
			.thenReturn(List.of(newest, next, lookahead));
		when(chatNoticeRepository.findVisibleByIdAndRoomId(999L, 100L, 42L)).thenReturn(Optional.of(pinned));

		var page = service.listNotices(principal(42L), 100L, null, 2);

		assertThat(page.items()).extracting(item -> item.noticeId()).containsExactly(903L, 902L);
		assertThat(page.nextCursor()).isNotNull();
		assertThat(page.pinnedNotice().noticeId()).isEqualTo(999L);
		assertThat(page.pinnedNotice().pinned()).isTrue();
		assertThat(page.items()).extracting(item -> item.pinned()).containsExactly(false, false);
	}

	@Test
	void pinReplacesRoomPinnedNoticeAndUnpinIsStaleSafe() {
		User me = user(42L);
		User sender = user(77L);
		ChatRoom room = roomOf(RoomType.direct, 100L);
		ChatMember member = ChatMember.join(room, me);
		ChatNotice notice = notice(901L, room, message(501L, room, sender, "pin"), me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatNoticeRepository.findVisibleByIdAndRoomId(901L, 100L, 42L)).thenReturn(Optional.of(notice));

		var pinned = service.pinNotice(principal(42L), 100L, 901L);

		assertThat(room.getPinnedNoticeId()).isEqualTo(901L);
		assertThat(pinned.pinned()).isTrue();

		room.pinNotice(999L);
		service.unpinNotice(principal(42L), 100L, 901L);

		assertThat(room.getPinnedNoticeId()).isEqualTo(999L);
		verify(chatRoomRepository).clearPinnedNoticeIfMatches(100L, 901L);
	}

	@Test
	void pinRejectsNoticeFromAnotherRoom() {
		User me = user(42L);
		ChatRoom room = roomOf(RoomType.direct, 100L);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L))
			.thenReturn(Optional.of(ChatMember.join(room, me)));
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatNoticeRepository.findVisibleByIdAndRoomId(901L, 100L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.pinNotice(principal(42L), 100L, 901L))
			.isInstanceOf(ChatNoticeNotFoundException.class);
	}

	@Test
	void missingRoomIsReportedBeforeMembershipCheck() {
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.listNotices(principal(42L), 100L, null, 20))
			.isInstanceOf(ChatRoomNotFoundException.class);

		verify(chatMemberRepository, never()).findActiveByRoomIdAndUserId(100L, 42L);
	}

	private AuthenticatedUser principal(Long userId) {
		return new AuthenticatedUser(userId, "user" + userId + "@example.com", UserRole.user, UserStatus.active);
	}

	private User user(Long id) {
		User user = User.createEmailUser(
			"user" + id + "@example.com",
			"hash",
			"user" + id,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
		setField(user, "id", id);
		return user;
	}

	private ChatRoom roomOf(RoomType type, Long id) {
		ChatRoom room = switch (type) {
			case direct -> ChatRoom.direct(42L, 77L);
			case group -> ChatRoom.group(7L);
			case question -> ChatRoom.question(9L, 42L, 77L);
		};
		setField(room, "id", id);
		return room;
	}

	private Message message(Long id, ChatRoom room, User sender, String content) {
		Message message = Message.text(room, sender, content, OffsetDateTime.parse("2026-07-21T10:00:00+09:00"));
		setField(message, "id", id);
		return message;
	}

	private ChatNotice notice(Long id, ChatRoom room, Message message, User createdBy) {
		ChatNotice notice = ChatNotice.create(room, message, createdBy, OffsetDateTime.parse("2026-07-21T11:00:00+09:00"));
		setField(notice, "id", id);
		return notice;
	}

	private void setField(Object target, String fieldName, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
