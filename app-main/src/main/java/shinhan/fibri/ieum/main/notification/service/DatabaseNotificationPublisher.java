package shinhan.fibri.ieum.main.notification.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.repository.NotificationEventRepository;
import shinhan.fibri.ieum.main.notification.repository.NotificationRepository;
import shinhan.fibri.ieum.main.notification.sse.NotificationSsePayload;
import shinhan.fibri.ieum.main.notification.sse.OutboundEvent;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;
import java.time.OffsetDateTime;

@Component
public class DatabaseNotificationPublisher implements NotificationPublisher {

	private final NotificationRepository notificationRepository;
	private final NotificationEventRepository notificationEventRepository;
	private final SseConnectionRegistry registry;

	public DatabaseNotificationPublisher(
		NotificationRepository notificationRepository,
		NotificationEventRepository notificationEventRepository,
		SseConnectionRegistry registry
	) {
		this.notificationRepository = notificationRepository;
		this.notificationEventRepository = notificationEventRepository;
		this.registry = registry;
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
		pushAfterCommit(userId, event);
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
			pushAfterCommit(userId, event);
			return true;
		}).orElse(false);
	}

	private void pushAfterCommit(Long userId, OutboundEvent event) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			pushIfOnline(userId, event);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				pushIfOnline(userId, event);
			}
		});
	}

	private void pushIfOnline(Long userId, OutboundEvent event) {
		if (registry.isOnline(userId)) {
			registry.push(userId, event);
		}
	}
}
