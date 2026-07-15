package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomSummaryResponse;
import shinhan.fibri.ieum.main.chat.exception.BlockedChatException;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.NotFriendsException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.exception.SelfChatRoomException;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

class ChatServiceTest {

	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final FriendService friendService = org.mockito.Mockito.mock(FriendService.class);
	private final MeetingRepository meetingRepository = org.mockito.Mockito.mock(MeetingRepository.class);
	private final QuestionRepository questionRepository = org.mockito.Mockito.mock(QuestionRepository.class);
	private final AnswerRepository answerRepository = org.mockito.Mockito.mock(AnswerRepository.class);
	private final ChatRoomLifecycle chatRoomLifecycle = org.mockito.Mockito.mock(ChatRoomLifecycle.class);
	private final ChatRoomSummaryQueryService chatRoomSummaryQueryService = org.mockito.Mockito.mock(ChatRoomSummaryQueryService.class);
	private final ChatRoomListChangeEmitter chatRoomListChangeEmitter = org.mockito.Mockito.mock(ChatRoomListChangeEmitter.class);
	private final List<TransactionDefinition> transactionDefinitions = new ArrayList<>();
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
	private final ChatService service = new ChatService(
		userRepository,
		chatRoomRepository,
		chatMemberRepository,
		messageRepository,
		friendService,
		meetingRepository,
		questionRepository,
		answerRepository,
		chatRoomLifecycle,
		chatRoomSummaryQueryService,
		chatRoomListChangeEmitter,
		transactionManager
	);

	@Test
	void createQuestionRoomReturnsLifecycleRoomWithoutFriendshipRequirement() {
		User me = user(42L, "me@example.com", "me");
		Question question = Question.create(5L, 42L, "title", "content");
		ChatRoom room = room(ChatRoom.question(9L, 42L, 77L), 100L);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L)).thenReturn(Optional.of(question));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(user(77L, "target@example.com", "target")));
		when(answerRepository.existsByQuestionIdAndAuthorIdAndAiFalse(9L, 77L)).thenReturn(true);
		when(friendService.hasBlockBetween(42L, 77L)).thenReturn(false);
		when(chatRoomLifecycle.getOrCreateQuestionRoom(9L, 42L, 77L)).thenReturn(100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));

		var response = service.createQuestionRoom(principal(42L), 9L, 77L);

		assertThat(response.roomId()).isEqualTo(100L);
		assertThat(response.roomType()).isEqualTo(RoomType.question);
		assertThat(response.questionId()).isEqualTo(9L);
		verify(friendService, never()).areFriends(42L, 77L);
		verify(chatRoomLifecycle).getOrCreateQuestionRoom(9L, 42L, 77L);
		verify(chatRoomListChangeEmitter, never()).upsert(any(), any());
	}

	@Test
	void createQuestionRoomDelegatesRepeatedRequestsToLifecycle() {
		User me = user(42L, "me@example.com", "me");
		Question question = Question.create(5L, 42L, "title", "content");
		ChatRoom room = room(ChatRoom.question(9L, 42L, 77L), 100L);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L)).thenReturn(Optional.of(question));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(user(77L, "target@example.com", "target")));
		when(answerRepository.existsByQuestionIdAndAuthorIdAndAiFalse(9L, 77L)).thenReturn(true);
		when(friendService.hasBlockBetween(42L, 77L)).thenReturn(false);
		when(chatRoomLifecycle.getOrCreateQuestionRoom(9L, 42L, 77L)).thenReturn(100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));

		var first = service.createQuestionRoom(principal(42L), 9L, 77L);
		var second = service.createQuestionRoom(principal(42L), 9L, 77L);

		assertThat(first.roomId()).isEqualTo(second.roomId());
		verify(chatRoomLifecycle, times(2)).getOrCreateQuestionRoom(9L, 42L, 77L);
	}

	@Test
	void createQuestionRoomRunsTheWholeOperationInOneNewTransaction() {
		stubQuestionRoomCreation();

		service.createQuestionRoom(principal(42L), 9L, 77L);

		assertThat(transactionDefinitions)
			.singleElement()
			.satisfies(definition -> assertThat(definition.getPropagationBehavior())
				.isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
	}

	@Test
	void createQuestionRoomRetriesTheWholeTransactionAfterRoomKeyRace() {
		stubQuestionRoomCreation();
		when(chatRoomLifecycle.getOrCreateQuestionRoom(9L, 42L, 77L))
			.thenThrow(new DataIntegrityViolationException("uidx_chat_rooms_room_key"))
			.thenReturn(100L);

		var response = service.createQuestionRoom(principal(42L), 9L, 77L);

		assertThat(response.roomId()).isEqualTo(100L);
		assertThat(transactionDefinitions)
			.hasSize(2)
			.allSatisfy(definition -> assertThat(definition.getPropagationBehavior())
				.isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
		verify(questionRepository, times(2)).findActiveByIdForShare(9L);
		verify(answerRepository, times(2)).existsByQuestionIdAndAuthorIdAndAiFalse(9L, 77L);
	}

	@Test
	void createQuestionRoomRejectsNonAuthorInitiator() {
		User me = user(42L, "me@example.com", "me");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L))
			.thenReturn(Optional.of(Question.create(5L, 88L, "title", "content")));

		assertThatThrownBy(() -> service.createQuestionRoom(principal(42L), 9L, 77L))
			.isInstanceOf(QuestionForbiddenException.class);

		verify(answerRepository, never()).existsByQuestionIdAndAuthorIdAndAiFalse(9L, 77L);
		verify(chatRoomLifecycle, never()).getOrCreateQuestionRoom(9L, 42L, 77L);
	}

	@Test
	void createQuestionRoomRejectsTargetWithoutHumanAnswerIncludingAiOnly() {
		User me = user(42L, "me@example.com", "me");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(user(77L, "target@example.com", "target")));
		when(answerRepository.existsByQuestionIdAndAuthorIdAndAiFalse(9L, 77L)).thenReturn(false);

		assertThatThrownBy(() -> service.createQuestionRoom(principal(42L), 9L, 77L))
			.isInstanceOf(QuestionForbiddenException.class);

		verify(chatRoomLifecycle, never()).getOrCreateQuestionRoom(9L, 42L, 77L);
	}

	@Test
	void createQuestionRoomRejectsMissingOrDeletedQuestion() {
		User me = user(42L, "me@example.com", "me");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createQuestionRoom(principal(42L), 9L, 77L))
			.isInstanceOf(QuestionNotFoundException.class);
	}

	@Test
	void createQuestionRoomRejectsSelfTarget() {
		User me = user(42L, "me@example.com", "me");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));

		assertThatThrownBy(() -> service.createQuestionRoom(principal(42L), 9L, 42L))
			.isInstanceOf(SelfChatRoomException.class);

		verify(answerRepository, never()).existsByQuestionIdAndAuthorIdAndAiFalse(9L, 42L);
	}

	@Test
	void createQuestionRoomRejectsMissingOrDeletedTargetUser() {
		User me = user(42L, "me@example.com", "me");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createQuestionRoom(principal(42L), 9L, 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(answerRepository, never()).existsByQuestionIdAndAuthorIdAndAiFalse(9L, 77L);
		verify(chatRoomLifecycle, never()).getOrCreateQuestionRoom(9L, 42L, 77L);
	}

	@Test
	void createQuestionRoomRejectsBlockedPair() {
		User me = user(42L, "me@example.com", "me");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(user(77L, "target@example.com", "target")));
		when(answerRepository.existsByQuestionIdAndAuthorIdAndAiFalse(9L, 77L)).thenReturn(true);
		when(friendService.hasBlockBetween(42L, 77L)).thenReturn(true);

		assertThatThrownBy(() -> service.createQuestionRoom(principal(42L), 9L, 77L))
			.isInstanceOf(BlockedChatException.class);

		verify(chatRoomLifecycle, never()).getOrCreateQuestionRoom(9L, 42L, 77L);
	}

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
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L, 77L));
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
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L, 77L));
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
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L, 77L));
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
	void listRoomsDelegatesToAuthoritativeSummaryQuery() {
		ChatRoomSummaryResponse summary = new ChatRoomSummaryResponse(
			100L,
			RoomType.direct,
			null,
			null,
			null,
			false,
			true,
			3L,
			null
		);
		when(chatRoomSummaryQueryService.listForUser(42L, null)).thenReturn(List.of(summary));

		var response = service.listRooms(principal(42L), null);

		assertThat(response).containsExactly(summary);
		verify(chatRoomSummaryQueryService).listForUser(42L, null);
	}

	@Test
	void listRoomsPassesRequestedTypeToAuthoritativeSummaryQuery() {
		when(chatRoomSummaryQueryService.listForUser(42L, RoomType.direct)).thenReturn(List.of());

		assertThat(service.listRooms(principal(42L), RoomType.direct)).isEmpty();

		verify(chatRoomSummaryQueryService).listForUser(42L, RoomType.direct);
	}

	@Test
	void getRoomRequiresActiveMembershipAndReturnsMembers() {
		User me = user(42L, "me@example.com", "me");
		User friend = user(77L, "friend@example.com", "friend", "US");
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
		assertThat(response.members())
			.filteredOn(member -> member.userId().equals(77L))
			.singleElement()
			.extracting(member -> member.nationality())
			.isEqualTo("US");
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
	void listMessagesUsesCursorSpecificQueryWhenCursorIsPresent() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember meMember = ChatMember.join(room, me);
		OffsetDateTime cursorCreatedAt = OffsetDateTime.parse("2026-07-08T11:00:00+09:00");
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(meMember));
		when(messageRepository.findMessagesBeforeCursor(
			org.mockito.Mockito.eq(100L),
			org.mockito.Mockito.eq(cursorCreatedAt),
			org.mockito.Mockito.eq(502L),
			any()
		)).thenReturn(List.of());

		var response = service.listMessages(
			principal(42L),
			100L,
			ChatMessageCursor.encode(cursorCreatedAt, 502L),
			2
		);

		assertThat(response.items()).isEmpty();
		verify(messageRepository).findMessagesBeforeCursor(
			org.mockito.Mockito.eq(100L),
			org.mockito.Mockito.eq(cursorCreatedAt),
			org.mockito.Mockito.eq(502L),
			any()
		);
		verify(messageRepository, never()).findLatestMessagesByRoomId(org.mockito.Mockito.eq(100L), any());
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
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L));
	}

	@Test
	void setPinnedUpdatesPinnedAt() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		service.setPinned(principal(42L), 100L, true);
		assertThat(member.getPinnedAt()).isNotNull();
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L));

		service.setPinned(principal(42L), 100L, false);
		assertThat(member.getPinnedAt()).isNull();
		verify(chatRoomListChangeEmitter, times(2)).upsert(100L, List.of(42L));
	}

	@Test
	void setNotifyEnabledUpdatesNotifyFlag() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		service.setNotifyEnabled(principal(42L), 100L, false);

		assertThat(member.isNotifyEnabled()).isFalse();
		verify(chatRoomListChangeEmitter).upsert(100L, List.of(42L));
	}

	@Test
	void settingsChangesDoNotEmitListEventsWhenMembershipValidationFails() {
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.markRead(principal(42L), 100L))
			.isInstanceOf(NotRoomMemberException.class);
		assertThatThrownBy(() -> service.setPinned(principal(42L), 100L, true))
			.isInstanceOf(NotRoomMemberException.class);
		assertThatThrownBy(() -> service.setNotifyEnabled(principal(42L), 100L, false))
			.isInstanceOf(NotRoomMemberException.class);

		verify(chatRoomListChangeEmitter, never()).upsert(any(), any());
	}

	@Test
	void leaveRoomSetsLeftAt() {
		User me = user(42L, "me@example.com", "me");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember member = ChatMember.join(room, me);
		when(chatMemberRepository.findActiveByRoomIdAndUserId(100L, 42L)).thenReturn(Optional.of(member));

		service.leaveRoom(principal(42L), 100L);

		assertThat(member.getLeftAt()).isNotNull();
		verify(chatRoomListChangeEmitter).remove(100L, List.of(42L));
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
		verify(chatRoomListChangeEmitter, never()).remove(any(), any());
	}

	@Test
	void disbandGroupRoomDeletesRoomWhenPrincipalIsMeetingHost() {
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		User host = user(42L, "host@example.com", "host");
		User memberUser = user(77L, "member@example.com", "member");
		ChatMember hostMember = ChatMember.join(room, host);
		ChatMember member = ChatMember.join(room, memberUser);
		ChatMember leftMember = ChatMember.join(room, user(88L, "left@example.com", "left"));
		leftMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);
		when(chatMemberRepository.findActiveUserIdsByRoomId(100L)).thenReturn(List.of(42L, 77L));
		when(meetingRepository.existsByIdAndHostIdAndDeletedAtIsNull(7L, 42L)).thenReturn(true);

		service.disbandRoom(principal(42L), 100L);

		verify(chatRoomRepository).delete(room);
		verify(chatMemberRepository, never()).findActiveByRoomIdAndUserId(100L, 42L);
		verify(chatMemberRepository, never()).findByRoom_Id(100L);
		verify(chatRoomListChangeEmitter).remove(100L, List.of(42L, 77L));
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
		verify(chatRoomListChangeEmitter, never()).remove(any(), any());
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
		verify(chatRoomListChangeEmitter, never()).remove(any(), any());
	}

	@Test
	void disbandRoomRequiresActiveMembership() {
		ChatRoom room = room(ChatRoom.group(7L), 100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(false);

		assertThatThrownBy(() -> service.disbandRoom(principal(42L), 100L))
			.isInstanceOf(NotRoomMemberException.class);

		verify(chatRoomRepository, never()).delete(any(ChatRoom.class));
		verify(chatRoomListChangeEmitter, never()).remove(any(), any());
	}

	private AuthenticatedUser principal(Long userId) {
		return new AuthenticatedUser(userId, "user" + userId + "@example.com", UserRole.user, UserStatus.active);
	}

	private void stubQuestionRoomCreation() {
		User me = user(42L, "me@example.com", "me");
		Question question = Question.create(5L, 42L, "title", "content");
		ChatRoom room = room(ChatRoom.question(9L, 42L, 77L), 100L);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(me));
		when(questionRepository.findActiveByIdForShare(9L)).thenReturn(Optional.of(question));
		when(userRepository.findByIdAndDeletedAtIsNull(77L))
			.thenReturn(Optional.of(user(77L, "target@example.com", "target")));
		when(answerRepository.existsByQuestionIdAndAuthorIdAndAiFalse(9L, 77L)).thenReturn(true);
		when(friendService.hasBlockBetween(42L, 77L)).thenReturn(false);
		when(chatRoomLifecycle.getOrCreateQuestionRoom(9L, 42L, 77L)).thenReturn(100L);
		when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
	}

	private User user(Long id, String email, String nickname) {
		return user(id, email, nickname, "KR");
	}

	private User user(Long id, String email, String nickname, String nationality) {
		User user = User.createEmailUser(
			email,
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			nationality
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
