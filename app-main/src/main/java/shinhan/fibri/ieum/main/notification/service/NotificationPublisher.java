package shinhan.fibri.ieum.main.notification.service;

import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;

/**
 * 프로듀서가 보는 유일한 알림 발행 표면.
 *
 * <p>프로듀서는 완성된 문장이 아니라 {@link NotificationMessage}(키 + 파라미터)를 넘긴다.
 * 번역은 프론트 카탈로그가 담당하므로 이 계층은 어떤 언어도 알지 못한다.
 */
public interface NotificationPublisher {

	void publishDurable(Long userId, NotificationType type, NotificationMessage message, Long refId);

	void publishDurable(
		Long userId,
		NotificationType type,
		NotificationMessage message,
		Long refId,
		Boolean answerIsAi
	);

	boolean publishDurableOnce(
		Long userId,
		NotificationType type,
		NotificationMessage message,
		Long refId,
		Boolean answerIsAi,
		String eventKey
	);

	void publishEphemeral(Long userId, NotificationType type, NotificationMessage message, Long refId);
}
