package shinhan.fibri.ieum.main.chat.service;

import com.interaso.webpush.WebPush;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatchRequest;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushPayloadEncoder;

@Component
@ConditionalOnProperty(prefix = "app.web-push", name = "enabled", havingValue = "true")
public class WebPushChatNotificationPublisher implements ChatNotificationPublisher {

	private static final Logger log = LoggerFactory.getLogger(WebPushChatNotificationPublisher.class);
	private static final int PUSH_TTL_SECONDS = 300;
	private static final String TITLE = "새 메시지";
	private static final String BODY = "새 채팅 메시지가 도착했어요";

	private final ChatMemberRepository chatMemberRepository;
	private final WebPushPayloadEncoder payloadEncoder;
	private final WebPushDispatcher dispatcher;

	public WebPushChatNotificationPublisher(
		ChatMemberRepository chatMemberRepository,
		WebPushPayloadEncoder payloadEncoder,
		WebPushDispatcher dispatcher
	) {
		this.chatMemberRepository = chatMemberRepository;
		this.payloadEncoder = payloadEncoder;
		this.dispatcher = dispatcher;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void messageCreated(ChatPushTrigger trigger) {
		List<Long> recipientIds = chatMemberRepository.findPushRecipientUserIds(
			trigger.roomId(),
			trigger.senderId(),
			trigger.messageId()
		);
		if (recipientIds.isEmpty()) {
			return;
		}

		String topic = roomTopic(trigger.roomId());
		byte[] encodedPayload = payloadEncoder.encode(new ChatPushPayload(
			1,
			"chat",
			TITLE,
			BODY,
			"/chats/room/?chatId=" + trigger.roomId(),
			topic
		));
		WebPushDispatchRequest request = new WebPushDispatchRequest(
			encodedPayload,
			PUSH_TTL_SECONDS,
			topic,
			WebPush.Urgency.Normal
		);

		for (Long recipientId : recipientIds) {
			try {
				dispatcher.dispatch(recipientId, request);
			}
			catch (RuntimeException exception) {
				log.warn(
					"event=chat_push_dispatch_failed roomId={} messageId={} recipientId={} failureType={}",
					trigger.roomId(),
					trigger.messageId(),
					recipientId,
					exception.getClass().getSimpleName()
				);
			}
		}
	}

	private String roomTopic(long roomId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(("chat-room:" + roomId).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 24));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
