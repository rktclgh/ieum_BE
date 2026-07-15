package shinhan.fibri.ieum.main.chat.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.domain.User;
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
import shinhan.fibri.ieum.main.chat.dto.ChatCursorPage;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomDetailResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomSummaryResponse;
import shinhan.fibri.ieum.main.chat.exception.BlockedChatException;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.GroupLeaveViaMeetingException;
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
import shinhan.fibri.ieum.main.question.repository.QuestionTitleProjection;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
@RequiredArgsConstructor
public class ChatService {

	private static final int DEFAULT_MESSAGE_PAGE_SIZE = 50;
	private static final int MAX_MESSAGE_PAGE_SIZE = 50;

	private final UserRepository userRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMemberRepository chatMemberRepository;
	private final MessageRepository messageRepository;
	private final FriendService friendService;
	private final MeetingRepository meetingRepository;
	private final QuestionRepository questionRepository;
	private final AnswerRepository answerRepository;
	private final ChatRoomLifecycle chatRoomLifecycle;
	private final PlatformTransactionManager transactionManager;

	public ChatRoomResponse createDirectRoom(AuthenticatedUser principal, Long friendId) {
		try {
			return createDirectRoomInNewTransaction(principal, friendId);
		} catch (DataIntegrityViolationException exception) {
			if (!isChatRoomConstraintViolation(exception)) {
				throw exception;
			}
			return createDirectRoomInNewTransaction(principal, friendId);
		}
	}

	private ChatRoomResponse createDirectRoomInNewTransaction(AuthenticatedUser principal, Long friendId) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template.execute(status -> createDirectRoomInTransaction(principal, friendId));
	}

	private ChatRoomResponse createDirectRoomInTransaction(AuthenticatedUser principal, Long friendId) {
		User currentUser = findActiveUser(principal.userId());
		if (currentUser.getId().equals(friendId)) {
			throw new SelfChatRoomException();
		}
		User friend = findActiveUser(friendId);
		if (!friendService.areFriends(currentUser.getId(), friend.getId())) {
			throw new NotFriendsException();
		}
		if (friendService.hasBlockBetween(currentUser.getId(), friend.getId())) {
			throw new BlockedChatException();
		}

		ChatRoom room = chatRoomRepository.findByRoomKey(ChatRoom.directRoomKey(currentUser.getId(), friend.getId()))
			.orElseGet(() -> insertDirectRoom(currentUser, friend));
		restoreDirectMembers(room, currentUser, friend);
		return ChatRoomResponse.from(room, null);
	}

	public ChatRoomResponse createQuestionRoom(
		AuthenticatedUser principal,
		Long questionId,
		Long targetUserId
	) {
		try {
			return createQuestionRoomInNewTransaction(principal, questionId, targetUserId);
		} catch (DataIntegrityViolationException exception) {
			if (!isChatRoomConstraintViolation(exception)) {
				throw exception;
			}
			return createQuestionRoomInNewTransaction(principal, questionId, targetUserId);
		}
	}

	private ChatRoomResponse createQuestionRoomInNewTransaction(
		AuthenticatedUser principal,
		Long questionId,
		Long targetUserId
	) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template.execute(status -> createQuestionRoomInTransaction(principal, questionId, targetUserId));
	}

	private ChatRoomResponse createQuestionRoomInTransaction(
		AuthenticatedUser principal,
		Long questionId,
		Long targetUserId
	) {
		User currentUser = findActiveUser(principal.userId());
		Question question = questionRepository.findActiveByIdForShare(questionId)
			.orElseThrow(QuestionNotFoundException::new);
		if (!question.getAuthorId().equals(currentUser.getId())) {
			throw new QuestionForbiddenException();
		}
		if (currentUser.getId().equals(targetUserId)) {
			throw new SelfChatRoomException();
		}
		User targetUser = findActiveUser(targetUserId);
		if (!answerRepository.existsByQuestionIdAndAuthorIdAndAiFalse(questionId, targetUser.getId())) {
			throw new QuestionForbiddenException();
		}
		if (friendService.hasBlockBetween(currentUser.getId(), targetUser.getId())) {
			throw new BlockedChatException();
		}

		Long roomId = chatRoomLifecycle.getOrCreateQuestionRoom(questionId, currentUser.getId(), targetUser.getId());
		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(ChatRoomNotFoundException::new);
		return ChatRoomResponse.from(room, question.getTitle());
	}

	@Transactional(readOnly = true)
	public List<ChatRoomSummaryResponse> listRooms(AuthenticatedUser principal, RoomType roomType) {
		List<ChatRoom> rooms = roomType == null
			? chatRoomRepository.findActiveRoomsByUserId(principal.userId())
			: chatRoomRepository.findActiveRoomsByUserIdAndRoomType(principal.userId(), roomType);
		if (rooms.isEmpty()) {
			return List.of();
		}
		List<Long> roomIds = rooms.stream().map(ChatRoom::getId).toList();
		Map<Long, ChatMember> membersByRoomId = chatMemberRepository
			.findActiveByUserIdAndRoomIds(principal.userId(), roomIds)
			.stream()
			.collect(Collectors.toMap(member -> member.getRoom().getId(), Function.identity()));
		Map<Long, Long> unreadByRoomId = messageRepository.countUnreadByRoomIds(principal.userId(), roomIds)
			.stream()
			.collect(Collectors.toMap(
				MessageRepository.RoomUnreadCount::getRoomId,
				MessageRepository.RoomUnreadCount::getUnreadCount
			));
		Map<Long, Message> lastMessageByRoomId = messageRepository.findLastMessagesByRoomIds(roomIds)
			.stream()
			.collect(Collectors.toMap(message -> message.getRoom().getId(), Function.identity()));
		Map<Long, String> titleByQuestionId = findQuestionTitles(rooms);

		return rooms.stream()
			.filter(room -> membersByRoomId.containsKey(room.getId()))
			.map(room -> ChatRoomSummaryResponse.from(
				room,
				membersByRoomId.get(room.getId()),
				unreadByRoomId.getOrDefault(room.getId(), 0L),
				lastMessageByRoomId.get(room.getId()),
				titleByQuestionId.get(room.getQuestionId())
			))
			.sorted(roomSummaryComparator())
			.toList();
	}

	@Transactional(readOnly = true)
	public ChatRoomDetailResponse getRoom(AuthenticatedUser principal, Long roomId) {
		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(ChatRoomNotFoundException::new);
		List<ChatMember> members = chatMemberRepository.findByRoom_Id(roomId);
		ChatMember currentMember = members.stream()
			.filter(member -> member.isActive() && member.getUser().getId().equals(principal.userId()))
			.findFirst()
			.orElseThrow(NotRoomMemberException::new);
		return ChatRoomDetailResponse.from(room, currentMember, members, findQuestionTitle(room.getQuestionId()));
	}

	@Transactional(readOnly = true)
	public ChatCursorPage<ChatMessageResponse> listMessages(
		AuthenticatedUser principal,
		Long roomId,
		String cursor,
		Integer size
	) {
		findActiveMember(roomId, principal.userId());
		int pageSize = normalizeMessagePageSize(size);
		ChatMessageCursor decodedCursor = ChatMessageCursor.decode(cursor);
		PageRequest pageRequest = PageRequest.of(0, pageSize + 1);
		List<Message> messages = decodedCursor == null
			? messageRepository.findLatestMessagesByRoomId(roomId, pageRequest)
			: messageRepository.findMessagesBeforeCursor(
				roomId, decodedCursor.createdAt(), decodedCursor.messageId(), pageRequest
			);
		boolean hasNext = messages.size() > pageSize;
		List<Message> pageItems = messages.stream().limit(pageSize).toList();
		String nextCursor = hasNext ? ChatMessageCursor.encode(pageItems.getLast()) : null;
		return new ChatCursorPage<>(pageItems.stream().map(ChatMessageResponse::from).toList(), nextCursor);
	}

	@Transactional
	public void markRead(AuthenticatedUser principal, Long roomId) {
		findActiveMember(roomId, principal.userId()).markRead(java.time.OffsetDateTime.now());
	}

	@Transactional
	public void setPinned(AuthenticatedUser principal, Long roomId, boolean pinned) {
		findActiveMember(roomId, principal.userId()).setPinned(pinned, java.time.OffsetDateTime.now());
	}

	@Transactional
	public void setNotifyEnabled(AuthenticatedUser principal, Long roomId, boolean enabled) {
		findActiveMember(roomId, principal.userId()).setNotifyEnabled(enabled);
	}

	@Transactional
	public void leaveRoom(AuthenticatedUser principal, Long roomId) {
		ChatMember member = findActiveMember(roomId, principal.userId());
		if (member.getRoom().getRoomType() == RoomType.group) {
			throw new GroupLeaveViaMeetingException();
		}
		member.leave(java.time.OffsetDateTime.now());
	}

	@Transactional
	public void disbandRoom(AuthenticatedUser principal, Long roomId) {
		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(ChatRoomNotFoundException::new);
		if (!chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(roomId, principal.userId())) {
			throw new NotRoomMemberException();
		}
		if (room.getRoomType() != RoomType.group) {
			throw new IllegalArgumentException("Only group chat rooms can be disbanded");
		}
		if (!meetingRepository.existsByIdAndHostIdAndDeletedAtIsNull(room.getMeetingId(), principal.userId())) {
			throw new NotHostException();
		}
		chatRoomRepository.delete(room);
	}

	private ChatRoom insertDirectRoom(User currentUser, User friend) {
		return chatRoomRepository.saveAndFlush(ChatRoom.direct(currentUser.getId(), friend.getId()));
	}

	private void restoreDirectMembers(ChatRoom room, User currentUser, User friend) {
		List<ChatMember> members = chatMemberRepository.findByRoom_Id(room.getId());
		restoreMember(room, currentUser, members);
		restoreMember(room, friend, members);
	}

	private void restoreMember(ChatRoom room, User user, List<ChatMember> members) {
		members.stream()
			.filter(member -> member.getUser().getId().equals(user.getId()))
			.findFirst()
			.ifPresentOrElse(ChatMember::rejoin, () -> chatMemberRepository.save(ChatMember.join(room, user)));
	}

	private Map<Long, String> findQuestionTitles(List<ChatRoom> rooms) {
		List<Long> questionIds = rooms.stream()
			.map(ChatRoom::getQuestionId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		if (questionIds.isEmpty()) {
			return Map.of();
		}
		return questionRepository.findTitlesByIds(questionIds).stream()
			.collect(Collectors.toMap(
				QuestionTitleProjection::getQuestionId,
				QuestionTitleProjection::getTitle
			));
	}

	private String findQuestionTitle(Long questionId) {
		if (questionId == null) {
			return null;
		}
		return questionRepository.findTitlesByIds(List.of(questionId)).stream()
			.findFirst()
			.map(QuestionTitleProjection::getTitle)
			.orElse(null);
	}

	private User findActiveUser(Long userId) {
		return userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(UserNotFoundException::new);
	}

	private ChatMember findActiveMember(Long roomId, Long userId) {
		return chatMemberRepository.findActiveByRoomIdAndUserId(roomId, userId)
			.orElseThrow(NotRoomMemberException::new);
	}

	private int normalizeMessagePageSize(Integer size) {
		if (size == null) {
			return DEFAULT_MESSAGE_PAGE_SIZE;
		}
		if (size < 1) {
			throw new IllegalArgumentException("size must be positive");
		}
		return Math.min(size, MAX_MESSAGE_PAGE_SIZE);
	}

	private Comparator<ChatRoomSummaryResponse> roomSummaryComparator() {
		return Comparator
			.comparing(ChatRoomSummaryResponse::pinned).reversed()
			.thenComparing(
				response -> response.lastMessage() == null ? null : response.lastMessage().createdAt(),
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(ChatRoomSummaryResponse::roomId, Comparator.reverseOrder());
	}

	private boolean isChatRoomConstraintViolation(DataIntegrityViolationException exception) {
		String constraint = constraintName(exception);
		if (constraint == null) {
			return false;
		}
		String normalized = constraint.toLowerCase(Locale.ROOT);
		return normalized.contains("uidx_chat_rooms_room_key")
			|| normalized.contains("chat_rooms_room_key");
	}

	private String constraintName(DataIntegrityViolationException exception) {
		if (exception.getCause() instanceof ConstraintViolationException constraintViolation) {
			return constraintViolation.getConstraintName();
		}
		return exception.getMessage();
	}
}
