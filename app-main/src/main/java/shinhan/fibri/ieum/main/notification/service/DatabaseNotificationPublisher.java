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

	public DatabaseNotificationPublisher(
		NotificationRepository notificationRepository,
		NotificationEventRepository notificationEventRepository,
		SseConnectionRegistry registry,
		WebPushDispatcher webPushDispatcher,
		WebPushPayloadEncoder webPushPayloadEncoder
	) {
		this.notificationRepository = notificationRepository;
		this.notificationEventRepository = notificationEventRepository;
		this.registry = registry;
		this.webPushDispatcher = webPushDispatcher;
		this.webPushPayloadEncoder = webPushPayloadEncoder;
	}

	@Override
	public void publishEphemeral(Long userId, NotificationType type, String title, String body, Long refId) {
		if (registry.isOnline(userId)) {
			registry.push(userId, OutboundEvent.ephemeral(NotificationSsePayload.ephemeral(
				type, title, body, refId, OffsetDateTime.now()
			)));
		}
	}

	@Override
	public void publishDurable(Long userId, NotificationType type, String title, String body, Long refId) {
		publishDurable(userId, type, title, body, refId, null);
	}

	@Override
	public void publishDurable(
		Long userId,
		NotificationType type,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi
	) {
		Notification notification = notificationRepository.saveAndFlush(
			Notification.of(userId, type, title, body, refId, answerIsAi)
		);
		OutboundEvent event = OutboundEvent.durable(NotificationSsePayload.durable(
			notification.getId(),
			notification.getType(),
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
		String title,
		String body,
		Long refId,
		Boolean answerIsAi,
		String eventKey
	) {
		return notificationEventRepository.insertOnce(
			userId,
			type,
			title,
			body,
			refId,
			answerIsAi,
			eventKey
		).map(inserted -> {
			OutboundEvent event = OutboundEvent.durable(NotificationSsePayload.durable(
				inserted.notificationId(),
				type,
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
			byte[] encoded = webPushPayloadEncoder.encode(DurableNotificationPushPayload.from(payload));
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
