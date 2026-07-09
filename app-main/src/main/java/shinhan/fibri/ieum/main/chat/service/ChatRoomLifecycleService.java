package shinhan.fibri.ieum.main.chat.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
@RequiredArgsConstructor
public class ChatRoomLifecycleService implements ChatRoomLifecycle {

	private final UserRepository userRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMemberRepository chatMemberRepository;
	private final PlatformTransactionManager transactionManager;

	@Override
	public Long createGroupRoom(Long meetingId, Long hostUserId) {
		try {
			return createGroupRoomInNewTransaction(meetingId, hostUserId);
		} catch (DataIntegrityViolationException exception) {
			if (!isChatRoomConstraintViolation(exception)) {
				throw exception;
			}
			return createGroupRoomInNewTransaction(meetingId, hostUserId);
		}
	}

	private Long createGroupRoomInNewTransaction(Long meetingId, Long hostUserId) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template.execute(status -> createGroupRoomInTransaction(meetingId, hostUserId));
	}

	private Long createGroupRoomInTransaction(Long meetingId, Long hostUserId) {
		User host = findActiveUser(hostUserId);
		ChatRoom room = chatRoomRepository.findByMeetingId(meetingId)
			.orElseGet(() -> chatRoomRepository.saveAndFlush(ChatRoom.group(meetingId)));
		restoreMember(room, host, chatMemberRepository.findByRoom_Id(room.getId()));
		return room.getId();
	}

	@Override
	public Long getOrCreateQuestionRoom(Long questionId, Long firstUserId, Long secondUserId) {
		try {
			return getOrCreateQuestionRoomInNewTransaction(questionId, firstUserId, secondUserId);
		} catch (DataIntegrityViolationException exception) {
			if (!isChatRoomConstraintViolation(exception)) {
				throw exception;
			}
			return getOrCreateQuestionRoomInNewTransaction(questionId, firstUserId, secondUserId);
		}
	}

	private Long getOrCreateQuestionRoomInNewTransaction(Long questionId, Long firstUserId, Long secondUserId) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template.execute(status -> getOrCreateQuestionRoomInTransaction(questionId, firstUserId, secondUserId));
	}

	private Long getOrCreateQuestionRoomInTransaction(Long questionId, Long firstUserId, Long secondUserId) {
		User firstUser = findActiveUser(firstUserId);
		User secondUser = findActiveUser(secondUserId);
		ChatRoom room = chatRoomRepository.findByRoomKey(ChatRoom.questionRoomKey(questionId, firstUserId, secondUserId))
			.orElseGet(() -> chatRoomRepository.saveAndFlush(ChatRoom.question(questionId, firstUserId, secondUserId)));
		List<ChatMember> members = chatMemberRepository.findByRoom_Id(room.getId());
		restoreMember(room, firstUser, members);
		restoreMember(room, secondUser, members);
		return room.getId();
	}

	@Override
	@Transactional
	public void addMember(Long roomId, Long userId) {
		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(ChatRoomNotFoundException::new);
		User user = findActiveUser(userId);
		restoreMember(room, user, chatMemberRepository.findByRoom_Id(roomId));
	}

	@Override
	@Transactional
	public void removeMember(Long roomId, Long userId) {
		ChatMember member = chatMemberRepository.findActiveByRoomIdAndUserId(roomId, userId)
			.orElseThrow(NotRoomMemberException::new);
		member.leave(OffsetDateTime.now());
	}

	private void restoreMember(ChatRoom room, User user, List<ChatMember> members) {
		members.stream()
			.filter(member -> member.getUser().getId().equals(user.getId()))
			.findFirst()
			.ifPresentOrElse(ChatMember::rejoin, () -> chatMemberRepository.save(ChatMember.join(room, user)));
	}

	private User findActiveUser(Long userId) {
		return userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(UserNotFoundException::new);
	}

	private boolean isChatRoomConstraintViolation(DataIntegrityViolationException exception) {
		String constraint = constraintName(exception);
		if (constraint == null) {
			return false;
		}
		String normalized = constraint.toLowerCase(Locale.ROOT);
		return normalized.contains("uidx_chat_rooms_room_key")
			|| normalized.contains("chat_rooms_room_key")
			|| normalized.contains("uidx_chat_rooms_meeting_id")
			|| normalized.contains("chat_rooms_meeting_id");
	}

	private String constraintName(DataIntegrityViolationException exception) {
		if (exception.getCause() instanceof ConstraintViolationException constraintViolation) {
			return constraintViolation.getConstraintName();
		}
		return exception.getMessage();
	}
}
