package shinhan.fibri.ieum.main.ai.result;

import java.util.Optional;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * app-main 완료 결과 컨슈머 정산 판정. spec.md §6.3, §8.4.
 *
 * <p>app-ai 의 {@code AiJobMessageSettlement}과 같은 역할을 결과 소비 측에서 한다 — 별개 모듈이라
 * import 할 수 없으므로 사유 코드/스키마 검증 로직을 다시 선언한다.
 */
public final class AiResultMessageSettlement {

	// --- DLQ 사유 코드 (spec.md §8.4 "즉시 DLQ") ---
	public static final String REASON_UNPARSEABLE_PAYLOAD = "unparseable_payload";
	public static final String REASON_MISSING_SCHEMA_VERSION = "missing_schema_version";
	public static final String REASON_UNSUPPORTED_SCHEMA_VERSION = "unsupported_schema_version";
	public static final String REASON_INVALID_PAYLOAD = "invalid_payload";
	public static final String REASON_TICKET_NOT_FOUND = "ticket_not_found";
	public static final String REASON_COMPLETION_CONFLICT = "completion_conflict";

	// --- RETRY 사유 코드 (spec.md §8.4 "DB transient 오류") ---
	public static final String REASON_TRANSIENT_ERROR = "transient_error";

	/**
	 * 컨슈머가 받아들이는 최대 {@code schemaVersion}. 현재는 {@link AiJobTopology#SCHEMA_VERSION}과
	 * 같다(dual-read 창구가 열리면 이 값이 SCHEMA_VERSION 보다 커진다).
	 */
	public static final int SUPPORTED_MAX_SCHEMA_VERSION = AiJobTopology.SCHEMA_VERSION;

	private AiResultMessageSettlement() {
	}

	/**
	 * 봉투 수준 {@code schemaVersion} 검증. 누락되었거나 지원 범위를 넘으면 DLQ 사유 코드를 반환한다.
	 * 통과하면 {@link Optional#empty()}.
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
