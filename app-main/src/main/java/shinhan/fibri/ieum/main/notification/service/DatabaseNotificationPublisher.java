package shinhan.fibri.ieum.main.notification.service;

import com.interaso.webpush.WebPush;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.KoreanNotificationMessageFallback;
import shinhan.fibri.ieum.main.notification.message.NotificationLanguageResolver;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;
import shinhan.fibri.ieum.main.notification.push.DurableNotificationPushPayload;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatchRequest;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushPayloadEncoder;
import shinhan.fibri.ieum.main.notification.repository.NotificationEventRepository;
import shinhan.fibri.ieum.main.notification.repository.NotificationRepository;
import shinhan.fibri.ieum.main.notification.sse.NotificationSsePayload;
import shinhan.fibri.ieum.main.notification.sse.OutboundEvent;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@Component
public class DatabaseNotificationPublisher implements NotificationPublisher {

	private static final Logger log = LoggerFactory.getLogger(DatabaseNotificationPublisher.class);
	private static final int DURABLE_PUSH_TTL_SECONDS = 3_600;

	private final NotificationRepository notificationRepository;
	private final NotificationEventRepository notificationEventRepository;
	private final SseConnectionRegistry registry;
	private final WebPushDispatcher webPushDispatcher;
	private final WebPushPayloadEncoder webPushPayloadEncoder;
	private final KoreanNotificationMessageFallback koreanFallback;
	private final NotificationLanguageResolver languageResolver;

	public DatabaseNotificationPublisher(
		NotificationRepository notificationRepository,
		NotificationEventRepository notificationEventRepository,
		SseConnectionRegistry registry,
		WebPushDispatcher webPushDispatcher,
		WebPushPayloadEncoder webPushPayloadEncoder,
		KoreanNotificationMessageFallback koreanFallback,
		NotificationLanguageResolver languageResolver
	) {
		this.notificationRepository = notificationRepository;
		this.notificationEventRepository = notificationEventRepository;
		this.registry = registry;
		this.webPushDispatcher = webPushDispatcher;
		this.webPushPayloadEncoder = webPushPayloadEncoder;
		this.koreanFallback = koreanFallback;
		this.languageResolver = languageResolver;
	}

	@Override
	public void publishEphemeral(Long userId, NotificationType type, NotificationMessage message, Long refId) {
		if (registry.isOnline(userId)) {
			registry.push(userId, OutboundEvent.ephemeral(NotificationSsePayload.ephemeral(
				type,
				message,
				koreanFallback.renderTitle(message),
				koreanFallback.renderBody(message),
				refId,
				OffsetDateTime.now()
			)));
		}
	}

	@Override
	public void publishDurable(Long userId, NotificationType type, NotificationMessage message, Long refId) {
		publishDurable(userId, type, message, refId, null);
	}

	@Override
	public void publishDurable(
		Long userId,
		NotificationType type,
		NotificationMessage message,
		Long refId,
		Boolean answerIsAi
	) {
		Notification notification = notificationRepository.saveAndFlush(Notification.of(
			userId,
			type,
			message,
			koreanFallback.renderTitle(message),
			koreanFallback.renderBody(message),
			refId,
			answerIsAi
		));
		OutboundEvent event = OutboundEvent.durable(NotificationSsePayload.durable(
			notification.getId(),
			notification.getType(),
			message,
			notification.getTitle(),
			notification.getBody(),
			notification.getRefId(),
			notification.getAnswerIsAi(),
			notification.getCreatedAt()
		));
		fanOutAfterCommit(userId, event);
	}

	@Override
	public boolean publishDurableOnce(
		Long userId,
		NotificationType type,
		NotificationMessage message,
		Long refId,
		Boolean answerIsAi,
		String eventKey
	) {
		String title = koreanFallback.renderTitle(message);
		String body = koreanFallback.renderBody(message);
		return notificationEventRepository.insertOnce(
			userId,
			type,
			message,
			title,
			body,
			refId,
			answerIsAi,
			eventKey
		).map(inserted -> {
			OutboundEvent event = OutboundEvent.durable(NotificationSsePayload.durable(
				inserted.notificationId(),
				type,
				message,
				title,
				body,
				refId,
				answerIsAi,
				inserted.createdAt()
			));
			fanOutAfterCommit(userId, event);
			return true;
		}).orElse(false);
	}

	private void fanOutAfterCommit(Long userId, OutboundEvent event) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			fanOut(userId, event);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				fanOut(userId, event);
			}
		});
	}

	private void fanOut(Long userId, OutboundEvent event) {
		NotificationSsePayload payload = event.notificationPayload();
		try {
			if (registry.isOnline(userId)) {
				registry.push(userId, event);
			}
		}
		catch (RuntimeException exception) {
			log.warn(
				"event=notification_fanout_failed channel=sse userId={} notificationId={} type={} failureType={}",
				userId,
				payload.notificationId(),
				payload.type(),
				exception.getClass().getSimpleName()
			);
		}

		try {
			// 푸시는 브라우저/OS가 렌더하므로 여기서만 수신자 언어를 조회한다. 커밋 이후 경로라
			// 이 조회가 비즈니스 트랜잭션을 늘리지 않는다.
			String lang = languageResolver.resolve(userId);
			byte[] encoded = webPushPayloadEncoder.encode(DurableNotificationPushPayload.from(payload, lang));
			webPushDispatcher.dispatch(userId, new WebPushDispatchRequest(
				encoded,
				DURABLE_PUSH_TTL_SECONDS,
				"notification-" + payload.notificationId(),
				WebPush.Urgency.Normal
			));
		}
		catch (RuntimeException exception) {
			log.warn(
				"event=notification_fanout_failed channel=web_push userId={} notificationId={} type={} failureType={}",
				userId,
				payload.notificationId(),
				payload.type(),
				exception.getClass().getSimpleName()
			);
		}
	}
}
