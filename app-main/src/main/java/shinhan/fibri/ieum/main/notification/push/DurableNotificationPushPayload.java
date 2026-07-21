package shinhan.fibri.ieum.main.notification.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.sse.NotificationSsePayload;

/**
 * 웹푸시 페이로드 (durable 알림).
 *
 * <p>★ {@code version} 을 올리지 마라. {@code public/sw.js} 의 가드가 {@code payload.version !== 1}
 * 이면 폴백 알림으로 떨어지는데, 서비스워커는 클라이언트에 캐시돼 있어 즉시 갱신되지 않는다.
 * 2로 올리는 순간 구버전 SW를 가진 사용자가 전부 "새 알림" 같은 폴백 문구만 보게 된다.
 * 필드 추가는 구 SW가 무시하므로 가산적이고 안전하다 — 그래서 1을 유지한다.
 *
 * <p>{@code title}/{@code body}(ko 폴백)를 계속 싣는 이유도 같다. 구 SW는 이걸 쓰고,
 * 신 SW는 {@code messageKey} + {@code lang} 으로 자기 카탈로그에서 렌더한다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DurableNotificationPushPayload(
	int version,
	String kind,
	Long notificationId,
	NotificationType type,
	String title,
	String body,
	String messageKey,
	Map<String, String> messageParams,
	String lang,
	Long refId,
	Boolean answerIsAi
) {

	public static DurableNotificationPushPayload from(NotificationSsePayload source, String lang) {
		return new DurableNotificationPushPayload(
			1,
			"notification",
			source.notificationId(),
			source.type(),
			source.title(),
			source.body(),
			source.messageKey(),
			source.messageParams(),
			lang,
			source.refId(),
			source.answerIsAi()
		);
	}
}
