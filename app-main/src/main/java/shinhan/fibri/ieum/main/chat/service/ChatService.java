package shinhan.fibri.ieum.main.chat.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.chat.exception.BlockedChatException;
import shinhan.fibri.ieum.main.chat.exception.NotFriendsException;
import shinhan.fibri.ieum.main.chat.exception.SelfChatRoomException;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
@RequiredArgsConstructor
public class ChatService {

	private final UserRepository userRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMemberRepository chatMemberRepository;
	@SuppressWarnings("unused")
	private final MessageRepository messageRepository;
	private final FriendService friendService;

	@Transactional
	public ChatRoomResponse createDirectRoom(AuthenticatedUser principal, Long friendId) {
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
			.orElseGet(() -> createDirectRoom(currentUser, friend));
		restoreDirectMembers(room, currentUser, friend);
		return ChatRoomResponse.from(room);
	}

	private ChatRoom createDirectRoom(User currentUser, User friend) {
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

	private User findActiveUser(Long userId) {
		return userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(UserNotFoundException::new);
	}
}
