package shinhan.fibri.ieum.main.ai.outbox.repository;

import java.util.Objects;
import java.util.UUID;

/**
 * 클레임 쿼리({@code UPDATE ... RETURNING}, spec.md §7.2)가 돌려주는 발행 대상 한 건.
 *
 * <p>{@code payload}는 이미 완성된 wire 메시지 JSON이다 — relay는 이걸 <b>그대로</b> 바이트로 실어 보낸다.
 * 다시 직렬화하지 않는다(그러면 golden fixture와 어긋날 수 있다).
 */
public record ClaimedAiJob(
	long outboxId,
	UUID jobId,
	String jobType,
	long jobKey,
	String routingKey,
	int schemaVersion,
	String payload,
	int attempts
) {

	public ClaimedAiJob {
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(jobType, "jobType must not be null");
		Objects.requireNonNull(routingKey, "routingKey must not be null");
		Objects.requireNonNull(payload, "payload must not be null");
	}

	/**
	 * 네이티브 쿼리 결과 한 행을 매핑한다. 컬럼 순서는
	 * {@code outbox_id, job_id, job_type, job_key, routing_key, schema_version, payload, attempts}.
	 *
	 * <p>{@code payload}는 {@code jsonb}라 드라이버가 {@code PGobject}로 돌려준다. app-main은 postgresql
	 * 드라이버를 {@code testRuntimeOnly}로만 갖고 있어 컴파일 타임에 그 타입을 참조할 수 없으므로
	 * {@code toString()}(= jsonb 원문)으로 받는다.
	 */
	public static ClaimedAiJob fromRow(Object[] row) {
		return new ClaimedAiJob(
			((Number) row[0]).longValue(),
			row[1] instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(row[1])),
			String.valueOf(row[2]),
			((Number) row[3]).longValue(),
			String.valueOf(row[4]),
			((Number) row[5]).intValue(),
			String.valueOf(row[6]),
			((Number) row[7]).intValue()
		);
	}
}
