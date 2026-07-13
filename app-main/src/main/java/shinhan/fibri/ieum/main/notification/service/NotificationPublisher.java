package shinhan.fibri.ieum.main.notification.service;

import shinhan.fibri.ieum.main.notification.domain.NotificationType;

public interface NotificationPublisher {

	void publishDurable(Long userId, NotificationType type, String title, String body, Long refId);

	void publishDurable(
		Long userId,
		NotificationType type,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi
	);

	boolean publishDurableOnce(
		Long userId,
		NotificationType type,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi,
		String eventKey
	);

	void publishEphemeral(Long userId, NotificationType type, String title, String body, Long refId);
}
