package shinhan.fibri.ieum.main.notification.sse;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;

/**
 * SSE {@code notification} 이벤트 페이로드.
 *
 * <p>{@code messageKey}/{@code messageParams}가 현재 계약이고 클라이언트는 이걸로 수신자 언어를 렌더한다.
 * {@code title}/{@code body}는 ko 폴백으로 계속 함께 싣는다 — 키 렌더를 모르는 구버전 클라이언트가 있고,
 * 이 레코드가 {@code title} non-null을 요구하기 때문이다.
 */
public record NotificationSsePayload(
	Long notificationId,
	NotificationType type,
	String title,
	String body,
	String messageKey,
	Map<String, String> messageParams,
	Long refId,
	Boolean answerIsAi,
	OffsetDateTime createdAt,
	boolean persistent
) implements SseEventPayload {

	public NotificationSsePayload {
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(title, "title must not be null");
		Objects.requireNonNull(createdAt, "createdAt must not be null");
		if (persistent && notificationId == null) {
			throw new IllegalArgumentException("persistent notification requires notificationId");
		}
		if (!persistent && notificationId != null) {
			throw new IllegalArgumentException("ephemeral notification must not have notificationId");
		}
		messageParams = messageParams == null ? Map.of() : Map.copyOf(messageParams);
	}

	public static NotificationSsePayload durable(
		Long notificationId,
		NotificationType type,
		NotificationMessage message,
		String title,
		String body,
		Long refId,
		OffsetDateTime createdAt
	) {
		return durable(notificationId, type, message, title, body, refId, null, createdAt);
	}

	public static NotificationSsePayload durable(
		Long notificationId,
		NotificationType type,
		NotificationMessage message,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi,
		OffsetDateTime createdAt
	) {
		return new NotificationSsePayload(
			notificationId,
			type,
			title,
			body,
			message == null ? null : message.key(),
			message == null ? Map.of() : message.params(),
			refId,
			answerIsAi,
			createdAt,
			true
		);
	}

	public static NotificationSsePayload ephemeral(
		NotificationType type,
		NotificationMessage message,
		String title,
		String body,
		Long refId,
		OffsetDateTime createdAt
	) {
		return new NotificationSsePayload(
			null,
			type,
			title,
			body,
			message == null ? null : message.key(),
			message == null ? Map.of() : message.params(),
			refId,
			null,
			createdAt,
			false
		);
	}
}
