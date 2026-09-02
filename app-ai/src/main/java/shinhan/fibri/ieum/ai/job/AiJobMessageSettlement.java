package shinhan.fibri.ieum.ai.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.OptionalLong;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * app-ai 컨슈머 정산 판정. spec.md §6.3, §8.2.
 *
 * <ul>
 *   <li>{@link #ACK} — 메시지를 큐에서 제거하고 재시도하지 않는다. 성공 종결(lane 제출 성공) 또는
 *       terminal 상태(이미 완료/취소/삭제/DEAD/이미 처리 중) 흡수를 뜻한다. spec.md §8.3 "ACK 시점".</li>
 *   <li>{@link #RETRY} — 재시도하면 나아질 수 있는 일시적 실패다(lane 포화/기능 비활성). 컨슈머는
 *       {@code AiJobDeadLetterPublisher#retryOrDeadLetter}에 위임한다 — {@code x-death} 카운트가
 *       {@code AiJobTopology.MAX_DELIVERY_ATTEMPTS}(5) 미만이면 {@code channel.basicNack(tag, false,
 *       false)}로 work 큐의 {@code x-dead-letter-exchange}를 통해 retry 큐(TTL 30s)로 보내고, 도달했으면
 *       DLQ 경로로 전환한다(spec.md §6.3).</li>
 *   <li>{@link #DLQ} — 재시도로 고칠 수 없는 조건이다(파싱 실패, 스키마 미지원, 잘못된 ID, 티켓 없음).
 *       {@code x-death} 카운트를 보지 않고 {@code AiJobDeadLetterPublisher#deadLetter}로 즉시 DLQ 로
 *       보낸다.</li>
 * </ul>
 */
public enum AiJobMessageSettlement {
	ACK,
	RETRY,
	DLQ;

	// --- DLQ 사유 코드 (spec.md §6.3 "재시도 없이 즉시 DLQ") ---
	public static final String REASON_UNPARSEABLE_PAYLOAD = "unparseable_payload";
	public static final String REASON_MISSING_SCHEMA_VERSION = "missing_schema_version";
	public static final String REASON_UNSUPPORTED_SCHEMA_VERSION = "unsupported_schema_version";
	public static final String REASON_INVALID_PAYLOAD = "invalid_payload";
	public static final String REASON_TICKET_NOT_FOUND = "ticket_not_found";

	// --- RETRY 사유 코드 ---
	public static final String REASON_DISPATCH_SATURATED = "dispatch_saturated";
	public static final String REASON_DISPATCH_DISABLED = "dispatch_disabled";
	/**
	 * 리스너 밖으로 새어나온 {@code RuntimeException}(예: {@code DataAccessException}, 예상치 못한
	 * {@code NullPointerException})을 잡아 재시도 상한을 거치도록 만들 때 쓰는 사유 코드. 컨슈머가
	 * dispatch/submit 호출을 감싸는 {@code try-catch}에서만 쓴다 — spec.md §6.3 재시도 상한이 이
	 * 경로에도 반드시 적용돼야 한다(리뷰 발견 사항: 예외가 그대로 새면 {@code x-death} 카운트를
	 * 건너뛴 채 work↔retry 큐를 영원히 순환한다).
	 */
	public static final String REASON_DISPATCH_EXCEPTION = "dispatch_exception";

	/**
	 * 컨슈머가 받아들이는 최대 {@code schemaVersion}. spec.md §6.5: "컨슈머는 schemaVersion &gt;
	 * SUPPORTED_MAX 이면 retry 하지 않고 DLQ 로 보낸다." 현재는 {@link AiJobTopology#SCHEMA_VERSION}과
	 * 같다(dual-read 창구가 열리면 이 값이 SCHEMA_VERSION 보다 커진다 — spec.md §6.5 버전 관리 규칙).
	 */
	public static final int SUPPORTED_MAX_SCHEMA_VERSION = AiJobTopology.SCHEMA_VERSION;

	/**
	 * 봉투 수준 {@code schemaVersion} 검증. 누락되었거나 지원 범위를 넘으면 DLQ 사유 코드를 반환한다.
	 * 통과하면 {@link Optional#empty()} — 컨슈머는 본문 역직렬화를 계속 진행해도 된다.
	 */
	public static Optional<String> validateSchemaVersion(Integer schemaVersion) {
		if (schemaVersion == null) {
			return Optional.of(REASON_MISSING_SCHEMA_VERSION);
		}
		if (schemaVersion > SUPPORTED_MAX_SCHEMA_VERSION) {
			return Optional.of(REASON_UNSUPPORTED_SCHEMA_VERSION);
		}
		return Optional.empty();
	}

	/**
	 * 양의 정수 ID 필드({@code questionId}, {@code answerId})를 엄격하게 읽는다(리뷰 발견 사항).
	 * {@link JsonNode#asLong(long)}은 소수/문자열/불리언 등 정수가 아닌 노드도 관대하게 변환해버려
	 * ({@code 42.9} → {@code 42}, {@code "42"} → {@code 42}, {@code true} → {@code 1}) 잘못된 값이
	 * 그대로 dispatch 되는 문제가 있다. 이 메서드는 노드가 <b>정수 JSON 타입</b>이고 {@code long} 범위에
	 * 들어올 때만 값을 반환한다 — 필드 누락, 정수가 아닌 타입(소수·문자열·불리언 등), 범위 초과, 0
	 * 이하는 전부 {@link OptionalLong#empty()}이며, 호출부는 이를 {@code REASON_INVALID_PAYLOAD}로
	 * 즉시 DLQ 처리해야 한다.
	 */
	public static OptionalLong readPositiveIntegralId(JsonNode root, String field) {
		JsonNode node = root.get(field);
		if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToLong()) {
			return OptionalLong.empty();
		}
		long value = node.longValue();
		return value > 0 ? OptionalLong.of(value) : OptionalLong.empty();
	}
}
