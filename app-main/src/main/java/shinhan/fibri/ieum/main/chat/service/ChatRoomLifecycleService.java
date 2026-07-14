package shinhan.fibri.ieum.main.chat.service;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

	@Override
	@Transactional
	public Long createGroupRoom(Long meetingId, Long hostUserId) {
		User host = findActiveUser(hostUserId);
		ChatRoom room = chatRoomRepository.findByMeetingId(meetingId)
			.orElseGet(() -> chatRoomRepository.saveAndFlush(ChatRoom.group(meetingId)));
		restoreMember(room, host, chatMemberRepository.findByRoom_Id(room.getId()));
		return room.getId();
	}

	@Override
	@Transactional
	public Long getOrCreateQuestionRoom(Long questionId, Long firstUserId, Long secondUserId) {
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
}
