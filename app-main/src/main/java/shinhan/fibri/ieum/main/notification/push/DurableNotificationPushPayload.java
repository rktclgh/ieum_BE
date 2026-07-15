package shinhan.fibri.ieum.main.notification.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.sse.NotificationSsePayload;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record DurableNotificationPushPayload(
	int version,
	String kind,
	Long notificationId,
	NotificationType type,
	String title,
	String body,
	Long refId,
	Boolean answerIsAi
) {

	public static DurableNotificationPushPayload from(NotificationSsePayload source) {
		return new DurableNotificationPushPayload(
			1,
			"notification",
			source.notificationId(),
			source.type(),
			source.title(),
			source.body(),
			source.refId(),
			source.answerIsAi()
		);
	}
}
