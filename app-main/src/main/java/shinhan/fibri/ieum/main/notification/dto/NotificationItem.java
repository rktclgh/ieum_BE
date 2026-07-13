package shinhan.fibri.ieum.main.notification.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

public record NotificationItem(
	Long notificationId,
	NotificationType type,
	String title,
	String body,
	Long refId,
	Boolean answerIsAi,
	boolean isRead,
	OffsetDateTime createdAt
) {

	public static NotificationItem from(Notification notification) {
		return new NotificationItem(
			notification.getId(),
			notification.getType(),
			notification.getTitle(),
			notification.getBody(),
			notification.getRefId(),
			notification.getAnswerIsAi(),
			notification.isRead(),
			notification.getCreatedAt()
		);
	}
}
