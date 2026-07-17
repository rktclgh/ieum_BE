package shinhan.fibri.ieum.main.chat.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.MessageType;
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

	private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);
	private static final int MAX_CONTENT_LENGTH = 2000;

	private final ChatMemberRepository chatMemberRepository;
	private final MessageRepository messageRepository;
	private final RoomEventPublisher roomEventPublisher;
	private final ChatNotificationPublisher chatNotificationPublisher;
	private final ChatRoomListChangeEmitter chatRoomListChangeEmitter;

	@Transactional
	public ChatMessageResponse send(AuthenticatedUser principal, Long roomId, SendChatMessageRequest request) {
		validatePayload(request);
		ChatMember member = chatMemberRepository.findActiveByRoomIdAndUserId(roomId, principal.userId())
			.orElseThrow(NotRoomMemberException::new);
		Message replyTo = findReplyTarget(member, request.replyToMessageId());
		restoreLeftMembersForReopenableRoom(member, principal.userId());
		Message message = messageRepository.save(toMessage(member, request, replyTo));
		List<Long> activeUserIds = chatMemberRepository.findActiveUserIdsByRoomId(roomId);
		List<ChatMember> activeMembers = chatMemberRepository.findActiveByRoomIdAndUserIds(roomId, activeUserIds);
		chatRoomListChangeEmitter.upsert(roomId, activeUserIds);
		WsMessageEvent event = WsMessageEvent.from(message);
		ChatPushTrigger pushTrigger = new ChatPushTrigger(
			message.getId(),
			message.getRoom().getId(),
			message.getSender().getId()
		);
		publishAfterCommit(event, activeMembers, pushTrigger);
		return ChatMessageResponse.from(message, member.getVisibleAfterMessageId());
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
		if (imageFileId != null && content != null && !content.isBlank()) {
			throw new InvalidChatMessageException("content and imageFileId cannot both be provided");
		}
	}

	private Message findReplyTarget(ChatMember member, Long replyToMessageId) {
		if (replyToMessageId == null) {
			return null;
		}
		Message target = messageRepository.findReplyTargetById(replyToMessageId)
			.orElseThrow(() -> new InvalidChatMessageException("reply target must be a visible user message in this room"));
		if (
			target.getDeletedAt() != null
				|| target.getMessageType() != MessageType.user
				|| !Objects.equals(target.getRoom().getId(), member.getRoom().getId())
				|| target.getId() <= member.getVisibleAfterMessageId()
		) {
			throw new InvalidChatMessageException("reply target must be a visible user message in this room");
		}
		return target;
	}

	private Message toMessage(ChatMember member, SendChatMessageRequest request, Message replyTo) {
		if (request.imageFileId() != null && (request.content() == null || request.content().isBlank())) {
			return Message.image(member.getRoom(), member.getUser(), request.imageFileId(), replyTo);
		}
		return Message.text(member.getRoom(), member.getUser(), request.content(), replyTo);
	}

	private void publishAfterCommit(
		WsMessageEvent event,
		List<ChatMember> recipients,
		ChatPushTrigger pushTrigger
	) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			publish(event, recipients, pushTrigger);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publish(event, recipients, pushTrigger);
			}
		});
	}

	private void publish(WsMessageEvent event, List<ChatMember> recipients, ChatPushTrigger pushTrigger) {
		try {
			roomEventPublisher.publishUserMessage(event, recipients);
		}
		catch (RuntimeException exception) {
			log.warn(
				"event=chat_fanout_failed channel=websocket roomId={} messageId={} failureType={}",
				event.roomId(),
				event.messageId(),
				exception.getClass().getSimpleName()
			);
		}

		try {
			chatNotificationPublisher.messageCreated(pushTrigger);
		}
		catch (RuntimeException exception) {
			log.warn(
				"event=chat_fanout_failed channel=web_push roomId={} messageId={} failureType={}",
				pushTrigger.roomId(),
				pushTrigger.messageId(),
				exception.getClass().getSimpleName()
			);
		}
	}
}
