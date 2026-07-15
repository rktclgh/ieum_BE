package shinhan.fibri.ieum.main.chat.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;

@Service
@RequiredArgsConstructor
public class OneToOneChatMemberService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMemberRepository chatMemberRepository;
	private final MessageRepository messageRepository;

	@Transactional
	public void addInitialMembers(ChatRoom room, User requester, User target) {
		chatMemberRepository.save(ChatMember.join(room, requester));
		chatMemberRepository.save(ChatMember.join(room, target));
	}

	@Transactional
	public void activateRequester(ChatRoom room, Long requesterUserId) {
		ChatMember requester = chatMemberRepository
			.findByRoomIdAndUserIdForUpdate(room.getId(), requesterUserId)
			.orElseThrow(NotRoomMemberException::new);
		if (!requester.isActive()) {
			requester.reactivateAfter(messageRepository.findMaxMessageIdByRoomId(room.getId()));
		}
	}

	@Transactional
	public ChatMember prepareSend(Long roomId, Long senderUserId) {
		ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
			.orElseThrow(ChatRoomNotFoundException::new);
		if (room.getRoomType() == RoomType.group) {
			throw new IllegalArgumentException("One-to-one send preparation does not support group rooms");
		}

		List<ChatMember> members = chatMemberRepository.findByRoomIdForUpdateOrderByUserId(roomId);
		if (members.size() != 2) {
			throw new IllegalStateException("One-to-one chat room must have exactly two members");
		}

		List<ChatMember> senderMembers = members.stream()
			.filter(member -> member.getId().getUserId().equals(senderUserId))
			.toList();
		if (senderMembers.size() > 1) {
			throw new IllegalStateException("One-to-one chat room must have exactly one sender member");
		}
		ChatMember sender = senderMembers.stream()
			.filter(ChatMember::isActive)
			.findFirst()
			.orElseThrow(NotRoomMemberException::new);

		List<ChatMember> peerMembers = members.stream()
			.filter(member -> !member.getId().getUserId().equals(senderUserId))
			.toList();
		if (peerMembers.size() != 1) {
			throw new IllegalStateException("One-to-one chat room must have exactly one peer member");
		}

		ChatMember peer = peerMembers.getFirst();
		if (!peer.isActive()) {
			peer.reactivateAfter(messageRepository.findMaxMessageIdByRoomId(roomId));
		}
		return sender;
	}
}
