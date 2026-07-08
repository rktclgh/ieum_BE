package shinhan.fibri.ieum.main.chat.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.SendChatMessageRequest;
import shinhan.fibri.ieum.main.chat.exception.InvalidChatMessageException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;

@Service
@RequiredArgsConstructor
public class ChatMessageService {

	private static final int MAX_CONTENT_LENGTH = 2000;

	private final ChatMemberRepository chatMemberRepository;
	private final MessageRepository messageRepository;
	private final RoomEventPublisher roomEventPublisher;
	private final ChatNotificationPublisher chatNotificationPublisher;

	@Transactional
	public ChatMessageResponse send(AuthenticatedUser principal, Long roomId, SendChatMessageRequest request) {
		validatePayload(request);
		ChatMember member = chatMemberRepository.findActiveByRoomIdAndUserId(roomId, principal.userId())
			.orElseThrow(NotRoomMemberException::new);
		restoreLeftMembersForReopenableRoom(member, principal.userId());
		Message message = messageRepository.save(toMessage(member, request));
		WsMessageEvent event = toEvent(message);
		publishAfterCommit(event);
		return ChatMessageResponse.from(message);
	}

	private void restoreLeftMembersForReopenableRoom(ChatMember senderMember, Long senderId) {
		if (senderMember.getRoom().getRoomType() == RoomType.group) {
			return;
		}
		chatMemberRepository.restoreLeftMembersByRoomIdExceptSender(senderMember.getRoom().getId(), senderId);
	}

	private void validatePayload(SendChatMessageRequest request) {
		if (request == null) {
			throw new InvalidChatMessageException("Message payload is required");
		}
		String content = request.content();
		UUID imageFileId = request.imageFileId();
		if ((content == null || content.isBlank()) && imageFileId == null) {
			throw new InvalidChatMessageException("content or imageFileId is required");
		}
		if (content != null && content.length() > MAX_CONTENT_LENGTH) {
			throw new InvalidChatMessageException("content must be 2000 characters or less");
		}
	}

	private Message toMessage(ChatMember member, SendChatMessageRequest request) {
		if (request.imageFileId() != null && (request.content() == null || request.content().isBlank())) {
			return Message.image(member.getRoom(), member.getUser(), request.imageFileId());
		}
		return Message.text(member.getRoom(), member.getUser(), request.content());
	}

	private WsMessageEvent toEvent(Message message) {
		ChatMessageResponse response = ChatMessageResponse.from(message);
		return new WsMessageEvent(
			response.messageId(),
			response.roomId(),
			response.senderId(),
			response.senderNickname(),
			response.content(),
			response.imageUrl(),
			response.createdAt()
		);
	}

	private void publishAfterCommit(WsMessageEvent event) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			publish(event);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publish(event);
			}
		});
	}

	private void publish(WsMessageEvent event) {
		roomEventPublisher.publish(event);
		chatNotificationPublisher.messageCreated(event);
	}
}
