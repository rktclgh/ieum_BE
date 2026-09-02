package shinhan.fibri.ieum.ai.job;

import java.util.Optional;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * app-ai 컨슈머 정산 판정. spec.md §6.3, §8.2.
 *
 * <ul>
 *   <li>{@link #ACK} — 메시지를 큐에서 제거하고 재시도하지 않는다. 성공 종결(lane 제출 성공) 또는
 *       terminal 상태(이미 완료/취소/삭제/DEAD/이미 처리 중) 흡수를 뜻한다. spec.md §8.3 "ACK 시점".</li>
 *   <li>{@link #RETRY} — {@code channel.basicNack(tag, false, false)} 로 work 큐의
 *       {@code x-dead-letter-exchange}를 통해 retry 큐(TTL 30s)로 보낸다. lane 포화/기능 비활성처럼
 *       재시도하면 나아질 수 있는 일시적 실패다.</li>
 *   <li>{@link #DLQ} — 재시도로 고칠 수 없는 조건이다(파싱 실패, 스키마 미지원, 잘못된 ID, 티켓 없음).
 *       Task 6 이전까지는 {@code AiJobDeadLetterPublisher}의 임시 구현이 {@link #RETRY}와 동일하게
 *       NACK 하지만, 판정 자체는 이 값으로 {@link #RETRY}와 구분된다(브리프 "구현 단계" 4번).</li>
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
}
