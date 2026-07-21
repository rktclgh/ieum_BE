package shinhan.fibri.ieum.main.notification.message;

import java.util.List;

/**
 * 알림 메시지 키 레지스트리.
 *
 * <p>이 목록이 SSOT다. 프론트 7개 카탈로그와 {@link KoreanNotificationMessageFallback}가
 * 정확히 이 키 집합을 구현한다. 호출부는 문자열 리터럴 대신 여기 상수만 참조한다.
 *
 * <p>AI 답변은 별도 키가 아니다 — {@link #ANSWER_CREATED} + {@code answerIsAi=true}로 구분하며
 * {@code AI ·} 접두는 프론트가 붙인다.
 */
public final class NotificationMessageKey {

	/** 내 질문에 답변이 달림 (사람·AI 공통). */
	public static final String ANSWER_CREATED = "notification.answer.created";

	/** 내 답변이 채택됨. */
	public static final String ANSWER_ACCEPTED = "notification.answer.accepted";

	/** 친구 요청 수신. params: {@code nickname} */
	public static final String FRIEND_REQUEST = "notification.friend.request";

	/** 주변에 새 질문 (ephemeral). params: {@code subject} — 사용자가 쓴 제목 원문 통과값 */
	public static final String RADIUS_QUESTION = "notification.radius.question";

	/** 주변에 새 모임 (ephemeral). params: {@code subject} — 사용자가 쓴 제목 원문 통과값 */
	public static final String RADIUS_MEETING = "notification.radius.meeting";

	/** 새 채팅 메시지 (웹푸시 전용). */
	public static final String CHAT_MESSAGE = "notification.chat.message";

	private static final List<String> ALL = List.of(
		ANSWER_CREATED,
		ANSWER_ACCEPTED,
		FRIEND_REQUEST,
		RADIUS_QUESTION,
		RADIUS_MEETING,
		CHAT_MESSAGE
	);

	private NotificationMessageKey() {
	}

	public static List<String> all() {
		return ALL;
	}
}
