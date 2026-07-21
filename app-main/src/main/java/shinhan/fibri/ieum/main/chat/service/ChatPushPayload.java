package shinhan.fibri.ieum.main.chat.service;

import java.util.Map;

/**
 * 웹푸시 페이로드 (채팅 메시지).
 *
 * <p>★ {@code version} 은 1을 유지한다. {@code public/sw.js} 가 {@code version !== 1} 이면 폴백 알림으로
 * 떨어지는데 서비스워커는 클라이언트에 캐시돼 있어 즉시 갱신되지 않는다. 필드 추가는 구 SW가
 * 무시하므로 가산적이고 안전하다. {@code title}/{@code body} 는 그 구 SW를 위한 ko 폴백이고,
 * 신 SW는 {@code messageKey} + {@code lang} 으로 자기 카탈로그에서 렌더한다.
 */
public record ChatPushPayload(
	int version,
	String kind,
	String title,
	String body,
	String messageKey,
	Map<String, String> messageParams,
	String lang,
	String url,
	String tag
) {
}
