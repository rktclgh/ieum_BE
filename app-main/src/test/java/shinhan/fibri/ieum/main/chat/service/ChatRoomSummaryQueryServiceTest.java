package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.dto.ChatReplyPreview;
import shinhan.fibri.ieum.main.notification.presence.UserPresenceQuery;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionTitleProjection;

class ChatRoomSummaryQueryServiceTest {

	private final ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final QuestionRepository questionRepository = org.mockito.Mockito.mock(QuestionRepository.class);
	private final Set<Long> onlineUserIds = new HashSet<>();
	private final UserPresenceQuery userPresenceQuery = onlineUserIds::contains;
	private final ChatRoomSummaryQueryService service = new ChatRoomSummaryQueryService(
		chatRoomRepository,
		chatMemberRepository,
		messageRepository,
		questionRepository,
		userPresenceQuery
	);

	@Test
	void listForUserBuildsRestSummaryWithExistingOrderingAndPersonalizedValues() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom normalRoom = room(ChatRoom.direct(42L, 77L), 100L);
		ChatRoom pinnedRoom = room(ChatRoom.question(10L, 42L, 88L), 200L);
		ChatMember normalMember = ChatMember.join(normalRoom, me);
		ChatMember pinnedMember = ChatMember.join(pinnedRoom, me);
		pinnedMember.setPinned(true, OffsetDateTime.parse("2026-07-08T12:00:00+09:00"));
		pinnedMember.setNotifyEnabled(false);
		Message normalLast = message(501L, normalRoom, friend, "normal", "2026-07-08T11:00:00+09:00");
		Message pinnedLast = message(502L, pinnedRoom, me, "pinned", "2026-07-08T10:00:00+09:00");
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(normalRoom, pinnedRoom));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L, 200L)))
			.thenReturn(List.of(normalMember, pinnedMember));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L, 200L)))
			.thenReturn(List.of(unread(100L, 3L)));
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(100L, 200L)))
			.thenReturn(List.of(normalLast, pinnedLast));
		when(questionRepository.findTitlesByIds(List.of(10L))).thenReturn(List.of(questionTitle(10L, "question")));

		var response = service.listForUser(42L, null);

		assertThat(response)
			.extracting(room -> room.roomId())
			.containsExactly(200L, 100L);
		assertThat(response.get(0).pinned()).isTrue();
		assertThat(response.get(0).notifyEnabled()).isFalse();
		assertThat(response.get(0).questionTitle()).isEqualTo("question");
		assertThat(response.get(0).lastMessage().content()).isEqualTo("pinned");
		assertThat(response.get(1).unreadCount()).isEqualTo(3L);
	}

	@Test
	void listForUserUsesTypeSpecificQueryWhenTypeIsPresent() {
		when(chatRoomRepository.findActiveRoomsByUserIdAndRoomType(42L, RoomType.direct))
			.thenReturn(List.of());

		assertThat(service.listForUser(42L, RoomType.direct)).isEmpty();

		verify(chatRoomRepository).findActiveRoomsByUserIdAndRoomType(42L, RoomType.direct);
		verify(chatRoomRepository, never()).findActiveRoomsByUserId(42L);
	}

	@Test
	void listForUserKeepsTheLastMessageReplyPreviewFlat() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		Message target = message(400L, room, friend, "original", "2026-07-08T10:00:00+09:00");
		Message lastMessage = Message.text(
			room,
			me,
			"reply",
			OffsetDateTime.parse("2026-07-08T11:00:00+09:00"),
			target
		);
		setField(lastMessage, "id", 501L);
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(room));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L))).thenReturn(List.of(member));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(100L))).thenReturn(List.of(lastMessage));

		var response = service.listForUser(42L, null);

		assertThat(response).singleElement().satisfies(summary -> {
			assertThat(summary.lastMessage().replyTo()).isEqualTo(
				new ChatReplyPreview(400L, 77L, "friend", "original", null)
			);
		});
		assertThat(ChatReplyPreview.class.getRecordComponents())
			.extracting(component -> component.getName())
			.doesNotContain("replyTo");
	}

	@Test
	void listForUserRedactsReplyPreviewWhoseParentPredatesRejoinBoundary() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		member.hideHistoryThrough(400L);
		Message hiddenParent = message(400L, room, friend, "original", "2026-07-08T10:00:00+09:00");
		Message lastMessage = Message.text(
			room,
			friend,
			"reply",
			OffsetDateTime.parse("2026-07-08T11:00:00+09:00"),
			hiddenParent
		);
		setField(lastMessage, "id", 501L);
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(room));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L))).thenReturn(List.of(member));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(100L))).thenReturn(List.of(lastMessage));

		var response = service.listForUser(42L, null);

		assertThat(response).singleElement().satisfies(summary ->
			assertThat(summary.lastMessage().replyTo()).isNull()
		);
	}

	@Test
	void listForUserReturnsDirectRoomWhenUserHasNoQuestionRooms() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom directRoom = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(directRoom, me);
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(directRoom));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L))).thenReturn(List.of(member));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(100L))).thenReturn(List.of());

		var response = service.listForUser(42L, null);

		assertThat(response).singleElement().satisfies(summary -> {
			assertThat(summary.questionId()).isNull();
			assertThat(summary.questionTitle()).isNull();
		});
		verify(questionRepository, never()).findTitlesByIds(org.mockito.ArgumentMatchers.anyList());
	}

	@Test
	void findActiveForRoomAndUsersBuildsOnlyRequestedActiveMemberSummaries() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember meMember = ChatMember.join(room, me);
		ChatMember friendMember = ChatMember.join(room, friend);
		friendMember.hideHistoryThrough(501L);
		Message meLast = message(501L, room, friend, "visible-to-me", "2026-07-08T11:00:00+09:00");
		when(chatMemberRepository.findActiveByRoomIdAndUserIds(100L, List.of(42L, 77L, 88L)))
			.thenReturn(List.of(meMember, friendMember));
		when(messageRepository.countUnreadByRoomIdAndUserIds(100L, List.of(42L, 77L)))
			.thenReturn(List.of(userUnread(42L, 1L)));
		when(messageRepository.findLastVisibleMessagesByRoomIdAndUserIds(100L, List.of(42L, 77L)))
			.thenReturn(List.of(userLastVisible(42L, meLast)));

		var response = service.findActiveForRoomAndUsers(100L, List.of(42L, 77L, 88L));

		assertThat(response.keySet()).containsExactlyInAnyOrder(42L, 77L);
		assertThat(response.get(42L).unreadCount()).isEqualTo(1L);
		assertThat(response.get(77L).unreadCount()).isZero();
		assertThat(response.get(42L).lastMessage().content()).isEqualTo("visible-to-me");
		assertThat(response.get(77L).lastMessage()).isNull();
		verify(messageRepository).findLastVisibleMessagesByRoomIdAndUserIds(100L, List.of(42L, 77L));
	}

	@Test
	void listForUserMarksDirectRoomCounterpartActiveWhenPresenceQuerySaysOnline() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom directRoom = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(directRoom, me);
		UUID profileFileId = UUID.fromString("11111111-2222-3333-4444-555555555555");
		onlineUserIds.add(77L);
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(directRoom));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L))).thenReturn(List.of(member));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(chatMemberRepository.findCounterpartsByRoomIds(42L, List.of(100L)))
			.thenReturn(List.of(counterpart(100L, 77L, "friend", profileFileId, "KR")));

		var response = service.listForUser(42L, null);

		assertThat(response).singleElement().satisfies(summary -> {
			assertThat(summary.counterpart()).isNotNull();
			assertThat(summary.counterpart().userId()).isEqualTo(77L);
			assertThat(summary.counterpart().nickname()).isEqualTo("friend");
			assertThat(summary.counterpart().profileImageUrl())
				.isEqualTo("/api/v1/files/%s".formatted(profileFileId));
			assertThat(summary.counterpart().nationality()).isEqualTo("KR");
			assertThat(summary.counterpart().active()).isTrue();
		});
	}

	@Test
	void listForUserKeepsCounterpartNonNullButInactiveWhenOffline() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom directRoom = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(directRoom, me);
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(directRoom));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L))).thenReturn(List.of(member));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(chatMemberRepository.findCounterpartsByRoomIds(42L, List.of(100L)))
			.thenReturn(List.of(counterpart(100L, 77L, "friend", null, "KR")));

		var response = service.listForUser(42L, null);

		assertThat(response).singleElement().satisfies(summary -> {
			assertThat(summary.counterpart()).isNotNull();
			assertThat(summary.counterpart().userId()).isEqualTo(77L);
			assertThat(summary.counterpart().profileImageUrl()).isNull();
			assertThat(summary.counterpart().active()).isFalse();
		});
	}

	@Test
	void listForUserLeavesGroupRoomCounterpartNullEvenWhenMultipleMemberRowsArrive() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom groupRoom = room(ChatRoom.group(9L), 300L);
		ChatMember member = ChatMember.join(groupRoom, me);
		onlineUserIds.add(77L);
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(groupRoom));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(300L))).thenReturn(List.of(member));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(300L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(300L))).thenReturn(List.of());
		when(chatMemberRepository.findCounterpartsByRoomIds(42L, List.of(300L)))
			.thenReturn(List.of(
				counterpart(300L, 77L, "friend", null, "KR"),
				counterpart(300L, 88L, "other", null, "US")
			));

		var response = service.listForUser(42L, null);

		assertThat(response).singleElement().satisfies(summary ->
			assertThat(summary.counterpart()).isNull()
		);
	}

	@Test
	void listForUserLeavesCounterpartNullWhenTheOtherMemberHasLeft() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom directRoom = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(directRoom, me);
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(directRoom));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L))).thenReturn(List.of(member));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(100L))).thenReturn(List.of());
		when(chatMemberRepository.findCounterpartsByRoomIds(42L, List.of(100L))).thenReturn(List.of());

		var response = service.listForUser(42L, null);

		assertThat(response).singleElement().satisfies(summary ->
			assertThat(summary.counterpart()).isNull()
		);
	}

	@Test
	void listForUserFetchesCounterpartsInASingleBatchQuery() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom firstRoom = room(ChatRoom.direct(42L, 77L), 100L);
		ChatRoom secondRoom = room(ChatRoom.question(10L, 42L, 88L), 200L);
		ChatMember firstMember = ChatMember.join(firstRoom, me);
		ChatMember secondMember = ChatMember.join(secondRoom, me);
		onlineUserIds.add(88L);
		when(chatRoomRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(firstRoom, secondRoom));
		when(chatMemberRepository.findActiveByUserIdAndRoomIds(42L, List.of(100L, 200L)))
			.thenReturn(List.of(firstMember, secondMember));
		when(messageRepository.countUnreadByRoomIds(42L, List.of(100L, 200L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIds(42L, List.of(100L, 200L))).thenReturn(List.of());
		when(questionRepository.findTitlesByIds(List.of(10L))).thenReturn(List.of(questionTitle(10L, "question")));
		when(chatMemberRepository.findCounterpartsByRoomIds(42L, List.of(100L, 200L)))
			.thenReturn(List.of(
				counterpart(100L, 77L, "friend", null, "KR"),
				counterpart(200L, 88L, "asker", null, "US")
			));

		var response = service.listForUser(42L, null);

		assertThat(response).hasSize(2);
		assertThat(response)
			.filteredOn(summary -> summary.roomId().equals(200L))
			.singleElement()
			.satisfies(summary -> assertThat(summary.counterpart().active()).isTrue());
		assertThat(response)
			.filteredOn(summary -> summary.roomId().equals(100L))
			.singleElement()
			.satisfies(summary -> assertThat(summary.counterpart().active()).isFalse());
		verify(chatMemberRepository, times(1))
			.findCounterpartsByRoomIds(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList());
	}

	@Test
	void findActiveForRoomAndUsersCrossReferencesCounterpartsWithoutExtraQueries() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember meMember = ChatMember.join(room, me);
		ChatMember friendMember = ChatMember.join(room, friend);
		onlineUserIds.add(77L);
		when(chatMemberRepository.findActiveByRoomIdAndUserIds(100L, List.of(42L, 77L)))
			.thenReturn(List.of(meMember, friendMember));
		when(messageRepository.countUnreadByRoomIdAndUserIds(100L, List.of(42L, 77L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIdAndUserIds(100L, List.of(42L, 77L)))
			.thenReturn(List.of());

		var response = service.findActiveForRoomAndUsers(100L, List.of(42L, 77L));

		assertThat(response.get(42L).counterpart().userId()).isEqualTo(77L);
		assertThat(response.get(42L).counterpart().nickname()).isEqualTo("friend");
		assertThat(response.get(42L).counterpart().active()).isTrue();
		assertThat(response.get(77L).counterpart().userId()).isEqualTo(42L);
		assertThat(response.get(77L).counterpart().active()).isFalse();
		verify(chatMemberRepository, never())
			.findCounterpartsByRoomIds(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList());
	}

	@Test
	void findActiveForRoomAndUsersLeavesGroupRoomCounterpartNull() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend");
		ChatRoom room = room(ChatRoom.group(9L), 300L);
		ChatMember meMember = ChatMember.join(room, me);
		ChatMember friendMember = ChatMember.join(room, friend);
		onlineUserIds.add(77L);
		when(chatMemberRepository.findActiveByRoomIdAndUserIds(300L, List.of(42L, 77L)))
			.thenReturn(List.of(meMember, friendMember));
		when(messageRepository.countUnreadByRoomIdAndUserIds(300L, List.of(42L, 77L))).thenReturn(List.of());
		when(messageRepository.findLastVisibleMessagesByRoomIdAndUserIds(300L, List.of(42L, 77L)))
			.thenReturn(List.of());

		var response = service.findActiveForRoomAndUsers(300L, List.of(42L, 77L));

		assertThat(response.get(42L).counterpart()).isNull();
		assertThat(response.get(77L).counterpart()).isNull();
	}

	private ChatMemberRepository.RoomCounterpartProjection counterpart(
		Long roomId,
		Long userId,
		String nickname,
		UUID profileFileId,
		String nationality
	) {
		return new ChatMemberRepository.RoomCounterpartProjection() {
			@Override
			public Long getRoomId() {
				return roomId;
			}

			@Override
			public Long getUserId() {
				return userId;
			}

			@Override
			public String getNickname() {
				return nickname;
			}

			@Override
			public UUID getProfileFileId() {
				return profileFileId;
			}

			@Override
			public String getNationality() {
				return nationality;
			}
		};
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

	private MessageRepository.UserUnreadCount userUnread(Long userId, Long count) {
		return new MessageRepository.UserUnreadCount() {
			@Override
			public Long getUserId() {
				return userId;
			}

			@Override
			public Long getUnreadCount() {
				return count;
			}
		};
	}

	private MessageRepository.UserLastVisibleMessage userLastVisible(Long userId, Message lastMessage) {
		return new MessageRepository.UserLastVisibleMessage() {
			@Override
			public Long getUserId() {
				return userId;
			}

			@Override
			public Message getLastMessage() {
				return lastMessage;
			}
		};
	}

	private QuestionTitleProjection questionTitle(Long questionId, String title) {
		return new QuestionTitleProjection() {
			@Override
			public Long getQuestionId() {
				return questionId;
			}

			@Override
			public String getTitle() {
				return title;
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
