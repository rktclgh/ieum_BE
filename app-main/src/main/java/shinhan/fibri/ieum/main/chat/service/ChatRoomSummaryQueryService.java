package shinhan.fibri.ieum.main.chat.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
import shinhan.fibri.ieum.main.chat.dto.ChatRoomSummaryResponse;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionTitleProjection;

@Service
@RequiredArgsConstructor
public class ChatRoomSummaryQueryService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMemberRepository chatMemberRepository;
	private final MessageRepository messageRepository;
	private final QuestionRepository questionRepository;

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

	@Transactional(propagation = Propagation.MANDATORY)
	public Map<Long, ChatRoomSummaryResponse> findActiveForRoomAndUsers(Long roomId, Collection<Long> userIds) {
		List<Long> requestedUserIds = normalizeUserIds(userIds);
		if (requestedUserIds.isEmpty()) {
			return Map.of();
		}
		List<ChatMember> members = chatMemberRepository.findActiveByRoomIdAndUserIdsForUpdate(roomId, requestedUserIds);
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
		Message lastMessage = messageRepository.findLastMessagesByRoomIds(List.of(roomId))
			.stream()
			.findFirst()
			.orElse(null);
		String questionTitle = findQuestionTitle(members.getFirst().getRoom().getQuestionId());

		return members.stream()
			.collect(Collectors.toMap(
				member -> member.getUser().getId(),
				member -> ChatRoomSummaryResponse.from(
					member.getRoom(),
					member,
					unreadByUserId.getOrDefault(member.getUser().getId(), 0L),
					lastMessage,
					questionTitle
				)
			));
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

	private List<Long> normalizeUserIds(Collection<Long> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		return userIds.stream()
			.filter(Objects::nonNull)
			.collect(Collectors.collectingAndThen(
				Collectors.toCollection(LinkedHashSet::new),
				List::copyOf
			));
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
