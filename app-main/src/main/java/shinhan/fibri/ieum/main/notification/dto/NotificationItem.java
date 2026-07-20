package shinhan.fibri.ieum.main.notification.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

/**
 * 알림센터 목록 항목.
 *
 * <p>{@code messageKey}가 있으면 클라이언트가 자기 카탈로그에서 수신자 언어로 렌더한다.
 * v37 마이그레이션 이전 행은 {@code messageKey}가 {@code null}이므로 {@code title}/{@code body}로 폴백한다.
 * {@code messageParams}는 null 대신 항상 맵이다(없으면 빈 맵) — 클라이언트가 null 분기를 안 해도 되게.
 */
public record NotificationItem(
	Long notificationId,
	NotificationType type,
	String title,
	String body,
	String messageKey,
	Map<String, String> messageParams,
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
			notification.getMessageKey(),
			notification.getMessageParams(),
			notification.getRefId(),
			notification.getAnswerIsAi(),
			notification.isRead(),
			notification.getCreatedAt()
		);
	}
}
