package shinhan.fibri.ieum.main.notification.message;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 한국어 폴백 렌더러 — <b>번역기가 아니다</b>.
 *
 * <p>두 군데서만 쓴다: ① {@code notifications.title}이 {@code NOT NULL}이고
 * {@code NotificationSsePayload}도 {@code title} non-null을 요구하므로 그 컬럼·필드를 채우는 용도,
 * ② 키 렌더를 모르는 구버전 클라이언트의 폴백. 서버는 ko 한 벌만 알면 되므로
 * 프론트 7개 카탈로그와 이중 관리되는 일이 없다.
 *
 * <p>이 클래스에 다른 언어를 추가하지 마라. 그 순간 같은 문장을 두 곳에서 관리하게 된다.
 */
@Component
public class KoreanNotificationMessageFallback {

	private static final Logger log = LoggerFactory.getLogger(KoreanNotificationMessageFallback.class);

	private record Copy(String title, String body) {
	}

	private static final Map<String, Copy> COPY = Map.of(
		NotificationMessageKey.ANSWER_CREATED, new Copy("새 답변", "회원님의 질문에 답변이 달렸어요"),
		NotificationMessageKey.ANSWER_ACCEPTED, new Copy("답변 채택", "회원님의 답변이 채택됐어요"),
		NotificationMessageKey.FRIEND_REQUEST, new Copy("친구 요청", "{nickname}님이 친구 요청을 보냈어요"),
		NotificationMessageKey.RADIUS_QUESTION, new Copy("주변 새 질문", "{subject}"),
		NotificationMessageKey.RADIUS_MEETING, new Copy("주변 새 모임", "{subject}"),
		NotificationMessageKey.CHAT_MESSAGE, new Copy("새 메시지", "새 채팅 메시지가 도착했어요")
	);

	/**
	 * 미등록 키여도 예외를 던지지 않는다 — 문자열 조립 실패가 비즈니스 트랜잭션을 롤백시키면 안 된다
	 * (notification/spec.md §6). 키 문자열 자체를 돌려주고 경고만 남긴다.
	 */
	public String renderTitle(NotificationMessage message) {
		Copy copy = COPY.get(message.key());
		if (copy == null) {
			log.warn("event=notification_copy_missing key={}", message.key());
			return message.key();
		}
		return substitute(copy.title(), message.params());
	}

	public String renderBody(NotificationMessage message) {
		Copy copy = COPY.get(message.key());
		if (copy == null) {
			return null;
		}
		return substitute(copy.body(), message.params());
	}

	/** 치환되지 않은 플레이스홀더는 그대로 남긴다 — 폴백 경로에서 예외를 던지는 것보다 낫다. */
	private String substitute(String template, Map<String, String> params) {
		if (template == null || params.isEmpty()) {
			return template;
		}
		String rendered = template;
		for (Map.Entry<String, String> param : params.entrySet()) {
			rendered = rendered.replace("{" + param.getKey() + "}", param.getValue());
		}
		return rendered;
	}
}
