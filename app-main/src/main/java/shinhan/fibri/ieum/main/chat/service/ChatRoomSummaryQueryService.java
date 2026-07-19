package shinhan.fibri.ieum.main.chat.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomCounterpartResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomSummaryResponse;
import shinhan.fibri.ieum.main.notification.presence.UserPresenceQuery;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionTitleProjection;

@Service
@RequiredArgsConstructor
public class ChatRoomSummaryQueryService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMemberRepository chatMemberRepository;
	private final MessageRepository messageRepository;
	private final QuestionRepository questionRepository;
	private final UserPresenceQuery userPresenceQuery;

	@Transactional(readOnly = true)
	public List<ChatRoomSummaryResponse> listForUser(Long userId, RoomType roomType) {
		List<ChatRoom> rooms = roomType == null
			? chatRoomRepository.findActiveRoomsByUserId(userId)
			: chatRoomRepository.findActiveRoomsByUserIdAndRoomType(userId, roomType);
		if (rooms.isEmpty()) {
			return List.of();
		}
		List<Long> roomIds = rooms.stream().map(ChatRoom::getId).toList();
		Map<Long, ChatMember> membersByRoomId = chatMemberRepository
			.findActiveByUserIdAndRoomIds(userId, roomIds)
			.stream()
			.collect(Collectors.toMap(member -> member.getRoom().getId(), Function.identity()));
		Map<Long, Long> unreadByRoomId = messageRepository.countUnreadByRoomIds(userId, roomIds)
			.stream()
			.collect(Collectors.toMap(
				MessageRepository.RoomUnreadCount::getRoomId,
				MessageRepository.RoomUnreadCount::getUnreadCount
			));
		Map<Long, Message> lastMessageByRoomId = messageRepository.findLastVisibleMessagesByRoomIds(userId, roomIds)
			.stream()
			.collect(Collectors.toMap(message -> message.getRoom().getId(), Function.identity()));
		Map<Long, String> titleByQuestionId = findQuestionTitles(rooms);
		List<Long> oneToOneRoomIds = rooms.stream()
			.filter(room -> room.getRoomType() != RoomType.group)
			.map(ChatRoom::getId)
			.toList();
		Map<Long, List<ChatMemberRepository.RoomCounterpartProjection>> counterpartsByRoomId = oneToOneRoomIds.isEmpty()
			? Map.of()
			: chatMemberRepository.findCounterpartsByRoomIds(userId, oneToOneRoomIds)
				.stream()
				.collect(Collectors.groupingBy(ChatMemberRepository.RoomCounterpartProjection::getRoomId));

		return rooms.stream()
			.filter(room -> membersByRoomId.containsKey(room.getId()))
			.map(room -> ChatRoomSummaryResponse.from(
				room,
				membersByRoomId.get(room.getId()),
				unreadByRoomId.getOrDefault(room.getId(), 0L),
				lastMessageByRoomId.get(room.getId()),
				room.getQuestionId() == null ? null : titleByQuestionId.get(room.getQuestionId()),
				resolveCounterpart(room.getRoomType(), counterpartsByRoomId.get(room.getId()))
			))
			.sorted(roomSummaryComparator())
			.toList();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Map<Long, ChatRoomSummaryResponse> findActiveForRoomAndUsers(Long roomId, List<Long> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return Map.of();
		}
		List<ChatMember> members = chatMemberRepository.findActiveByRoomIdAndUserIds(roomId, userIds);
		if (members.isEmpty()) {
			return Map.of();
		}
		List<Long> activeUserIds = members.stream()
			.map(member -> member.getUser().getId())
			.toList();
		Map<Long, Long> unreadByUserId = messageRepository.countUnreadByRoomIdAndUserIds(roomId, activeUserIds)
			.stream()
			.collect(Collectors.toMap(
				MessageRepository.UserUnreadCount::getUserId,
				MessageRepository.UserUnreadCount::getUnreadCount
			));
		Map<Long, Message> lastMessageByUserId = messageRepository
			.findLastVisibleMessagesByRoomIdAndUserIds(roomId, activeUserIds)
			.stream()
			.collect(Collectors.toMap(
				MessageRepository.UserLastVisibleMessage::getUserId,
				MessageRepository.UserLastVisibleMessage::getLastMessage
			));
		String questionTitle = findQuestionTitle(members.getFirst().getRoom().getQuestionId());
		List<ChatMember> counterpartPool = findCounterpartPool(roomId, members);

		return members.stream()
			.collect(Collectors.toMap(
				member -> member.getUser().getId(),
				member -> ChatRoomSummaryResponse.from(
					member.getRoom(),
					member,
					unreadByUserId.getOrDefault(member.getUser().getId(), 0L),
					lastMessageByUserId.get(member.getUser().getId()),
					questionTitle,
					resolveCounterpart(member.getRoom().getRoomType(), counterpartPool, member.getUser().getId())
				)
			));
	}

	/**
	 * counterpart는 요청된 유저가 아니라 방 전체에서 찾아야 한다. markRead/pin/notify는
	 * {@code List.of(myUserId)} 하나만 넘기므로, 요청 목록만 뒤지면 상대를 못 찾아 counterpart가
	 * null로 나가고 FE의 온라인 표시가 깜빡인다.
	 *
	 * <p>1:1 방은 활성 멤버가 최대 2명이므로, 이미 2명을 받았다면 그게 방 전체라 추가 조회가 필요 없다.
	 * group 방은 counterpart 자체가 없으므로 조회하지 않는다.
	 */
	private List<ChatMember> findCounterpartPool(Long roomId, List<ChatMember> requestedMembers) {
		if (requestedMembers.getFirst().getRoom().getRoomType() == RoomType.group) {
			return List.of();
		}
		if (requestedMembers.size() >= 2) {
			return requestedMembers;
		}
		return chatMemberRepository.findActiveByRoomId(roomId);
	}

	private ChatRoomCounterpartResponse resolveCounterpart(
		RoomType roomType,
		List<ChatMemberRepository.RoomCounterpartProjection> counterparts
	) {
		if (roomType == RoomType.group || counterparts == null || counterparts.size() != 1) {
			return null;
		}
		ChatMemberRepository.RoomCounterpartProjection counterpart = counterparts.getFirst();
		return ChatRoomCounterpartResponse.of(
			counterpart.getUserId(),
			counterpart.getNickname(),
			counterpart.getProfileFileId(),
			counterpart.getNationality(),
			userPresenceQuery.isOnline(counterpart.getUserId())
		);
	}

	private ChatRoomCounterpartResponse resolveCounterpart(RoomType roomType, List<ChatMember> members, Long userId) {
		if (roomType == RoomType.group) {
			return null;
		}
		return members.stream()
			.map(ChatMember::getUser)
			.filter(user -> !user.getId().equals(userId))
			.findFirst()
			.map(user -> ChatRoomCounterpartResponse.of(
				user.getId(),
				user.getNickname(),
				user.getProfileFileId(),
				user.getNationality(),
				userPresenceQuery.isOnline(user.getId())
			))
			.orElse(null);
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

	private Comparator<ChatRoomSummaryResponse> roomSummaryComparator() {
		return Comparator
			.comparing(ChatRoomSummaryResponse::pinned).reversed()
			.thenComparing(
				response -> response.lastMessage() == null ? null : response.lastMessage().createdAt(),
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(ChatRoomSummaryResponse::roomId, Comparator.reverseOrder());
	}
}
