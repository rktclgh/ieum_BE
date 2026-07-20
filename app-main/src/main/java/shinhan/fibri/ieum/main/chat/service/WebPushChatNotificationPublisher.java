package shinhan.fibri.ieum.main.chat.service;

import com.interaso.webpush.WebPush;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.notification.message.NotificationLanguageResolver;
import shinhan.fibri.ieum.main.notification.message.NotificationMessageKey;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatchRequest;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushPayloadEncoder;

@Component
@ConditionalOnProperty(prefix = "app.web-push", name = "enabled", havingValue = "true")
public class WebPushChatNotificationPublisher implements ChatNotificationPublisher {

	private static final Logger log = LoggerFactory.getLogger(WebPushChatNotificationPublisher.class);
	private static final int PUSH_TTL_SECONDS = 300;
	// 문구가 아니라 구버전 서비스워커용 ko 폴백이다. 실제 문구는 messageKey 로 프론트가 렌더한다.
	private static final String TITLE = "새 메시지";
	private static final String BODY = "새 채팅 메시지가 도착했어요";

	private final ChatMemberRepository chatMemberRepository;
	private final WebPushPayloadEncoder payloadEncoder;
	private final WebPushDispatcher dispatcher;
	private final NotificationLanguageResolver languageResolver;

	public WebPushChatNotificationPublisher(
		ChatMemberRepository chatMemberRepository,
		WebPushPayloadEncoder payloadEncoder,
		WebPushDispatcher dispatcher,
		NotificationLanguageResolver languageResolver
	) {
		this.chatMemberRepository = chatMemberRepository;
		this.payloadEncoder = payloadEncoder;
		this.dispatcher = dispatcher;
		this.languageResolver = languageResolver;
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
		Map<Long, String> languages = languageResolver.resolveAll(recipientIds);

		// ★ 페이로드에 lang 이 들어가면서 "한 번 인코딩해 전원에게 재사용"이 불가능해졌다.
		//   그렇다고 수신자 수만큼 인코딩하면 1GB RAM 환경에 부담이므로 언어별로 묶어
		//   서로 다른 언어 개수만큼만 인코딩한다(보통 1~2회).
		Map<String, WebPushDispatchRequest> requestByLanguage = new HashMap<>();
		for (Long recipientId : recipientIds) {
			String lang = languages.getOrDefault(recipientId, NotificationLanguageResolver.DEFAULT_LANGUAGE);
			WebPushDispatchRequest request = requestByLanguage.computeIfAbsent(
				lang,
				language -> new WebPushDispatchRequest(
					payloadEncoder.encode(chatPayload(trigger.roomId(), topic, language)),
					PUSH_TTL_SECONDS,
					topic,
					WebPush.Urgency.Normal
				)
			);
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

	private ChatPushPayload chatPayload(long roomId, String topic, String lang) {
		return new ChatPushPayload(
			1,
			"chat",
			// ko 폴백 — 키 렌더를 모르는 구버전 서비스워커가 이걸 쓴다.
			TITLE,
			BODY,
			NotificationMessageKey.CHAT_MESSAGE,
			Map.of(),
			lang,
			"/chats/room/?chatId=" + roomId,
			topic
		);
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
