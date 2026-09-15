package shinhan.fibri.ieum.main.ai.outbox.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;

/**
 * outbox 쓰기와 relay 클레임·정산을 함께 담는다.
 *
 * <p><b>시간은 전부 DB 쪽 {@code now()}로 통일한다.</b> {@code next_attempt_at}은 앱 시계로 찍히는데
 * 클레임 술어가 앱 시계와 비교하면 두 시계의 스큐만큼 늦거나 이르게 잡힌다. 모든 비교·스탬프를
 * 트랜잭션 시작 시각({@code now()})으로 맞추면 relay 인스턴스가 늘어나도 판단 기준이 하나로 남는다.
 * (남는 스큐는 "row 최초 생성 시각을 앱이 찍는다" 한 군데뿐이고, 그 값은
 * 최소 backoff 1초보다 작은 스큐라면 관측 가능한 차이를 만들지 않는다.)
 *
 * <p>정산 쿼리는 모두 {@code lease_token}으로 펜싱한다 — lease가 만료돼 다른 워커가 다시 클레임한
 * row를, 뒤늦게 confirm을 받은 옛 워커가 덮어쓰지 못하게 한다.
 */
public interface AiJobOutboxRepository extends JpaRepository<AiJobOutbox, Long> {

	/** 보존 삭제 1회 배치 크기. 단일 거대 DELETE로 테이블을 오래 잠그지 않는다(spec.md §7.5). */
	int RETENTION_DELETE_BATCH = 5_000;

	/**
	 * 발행 대상을 배타적으로 클레임한다(spec.md §7.2). 짧은 쓰기 TX 하나로 끝내고 즉시 커밋한다 —
	 * 브로커 왕복은 이 트랜잭션 <b>밖</b>에서 일어나야 한다.
	 */
	@Transactional
	default List<ClaimedAiJob> claim(
		String workerId, UUID leaseToken, long leaseSeconds, int batchSize, int maxAttempts
	) {
		if (workerId == null || workerId.isBlank()) {
			throw new IllegalArgumentException("workerId must not be blank");
		}
		if (leaseToken == null) {
			throw new IllegalArgumentException("leaseToken must not be null");
		}
		if (leaseSeconds <= 0) {
			throw new IllegalArgumentException("leaseSeconds must be positive: " + leaseSeconds);
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
		}
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be positive: " + maxAttempts);
		}
		return claimRows(workerId, leaseToken.toString(), leaseSeconds, batchSize, maxAttempts)
			.stream()
			.map(ClaimedAiJob::fromRow)
			.toList();
	}

	/**
	 * {@code attempts >= :maxAttempts} 인 row 는 후보에서 제외한다 — 이 UPDATE 가 {@code attempts}를
	 * 먼저 1 증가시키므로, 제외하지 않으면 {@code ck_ai_job_outbox_attempts}(0~20) CHECK 를 넘겨
	 * 배치 전체가 롤백될 수 있다. 상한에 도달한 row 는 {@link #recoverExpiredLeases}나
	 * {@code AiJobOutboxRelay#settleFailure}가 이미 {@code dead}로 못박아 여기 후보에 남지 않는 게
	 * 정상 경로지만, 방어적으로도 이 술어가 있어야 한다.
	 */
	@Query(value = """
		UPDATE ai_job_outbox
		   SET status = 'publishing',
		       attempts = attempts + 1,
		       lease_token = CAST(:leaseToken AS uuid),
		       lease_until = now() + (:leaseSeconds * INTERVAL '1 second'),
		       locked_by = :workerId,
		       updated_at = now()
		 WHERE outbox_id IN (
		         SELECT outbox_id
		           FROM ai_job_outbox
		          WHERE status IN ('pending', 'retry')
		            AND next_attempt_at <= now()
		            AND attempts < :maxAttempts
		          ORDER BY next_attempt_at, outbox_id
		          FOR UPDATE SKIP LOCKED
		          LIMIT :batchSize
		       )
		RETURNING outbox_id, job_id, job_type, job_key, routing_key, schema_version, payload, attempts
		""", nativeQuery = true)
	List<Object[]> claimRows(
		@Param("workerId") String workerId,
		@Param("leaseToken") String leaseToken,
		@Param("leaseSeconds") long leaseSeconds,
		@Param("batchSize") int batchSize,
		@Param("maxAttempts") int maxAttempts
	);

	/** confirm ACK 정산. {@code published_at}을 채워야 CHECK 제약을 만족한다. */
	@Transactional
	@Modifying
	@Query(value = """
		UPDATE ai_job_outbox
		   SET status = 'published',
		       published_at = now(),
		       lease_token = NULL,
		       lease_until = NULL,
		       locked_by = NULL,
		       last_error_code = NULL,
		       last_error_message = NULL,
		       updated_at = now()
		 WHERE outbox_id = :outboxId
		   AND status = 'publishing'
		   AND lease_token = CAST(:leaseToken AS uuid)
		""", nativeQuery = true)
	int markPublishedRow(@Param("outboxId") long outboxId, @Param("leaseToken") String leaseToken);

	@Transactional
	default int markPublished(long outboxId, UUID leaseToken) {
		return markPublishedRow(outboxId, requireLease(leaseToken));
	}

	/** NACK / return / confirm timeout 정산. backoff 만큼 뒤로 미룬다(spec.md §7.5). */
	@Transactional
	@Modifying
	@Query(value = """
		UPDATE ai_job_outbox
		   SET status = 'retry',
		       next_attempt_at = now() + (:backoffSeconds * INTERVAL '1 second'),
		       lease_token = NULL,
		       lease_until = NULL,
		       locked_by = NULL,
		       last_error_code = :errorCode,
		       last_error_message = :errorMessage,
		       updated_at = now()
		 WHERE outbox_id = :outboxId
		   AND status = 'publishing'
		   AND lease_token = CAST(:leaseToken AS uuid)
		""", nativeQuery = true)
	int markRetryRow(
		@Param("outboxId") long outboxId,
		@Param("leaseToken") String leaseToken,
		@Param("backoffSeconds") long backoffSeconds,
		@Param("errorCode") String errorCode,
		@Param("errorMessage") String errorMessage
	);

	@Transactional
	default int markRetry(
		long outboxId, UUID leaseToken, long backoffSeconds, String errorCode, String errorMessage
	) {
		if (backoffSeconds <= 0) {
			throw new IllegalArgumentException("backoffSeconds must be positive: " + backoffSeconds);
		}
		return markRetryRow(outboxId, requireLease(leaseToken), backoffSeconds, errorCode, errorMessage);
	}

	/** 재시도 상한 도달. row 자체를 {@code dead}로 남긴다 — 운영 증거다(spec.md §7.5). */
	@Transactional
	@Modifying
	@Query(value = """
		UPDATE ai_job_outbox
		   SET status = 'dead',
		       lease_token = NULL,
		       lease_until = NULL,
		       locked_by = NULL,
		       last_error_code = :errorCode,
		       last_error_message = :errorMessage,
		       updated_at = now()
		 WHERE outbox_id = :outboxId
		   AND status = 'publishing'
		   AND lease_token = CAST(:leaseToken AS uuid)
		""", nativeQuery = true)
	int markDeadRow(
		@Param("outboxId") long outboxId,
		@Param("leaseToken") String leaseToken,
		@Param("errorCode") String errorCode,
		@Param("errorMessage") String errorMessage
	);

	@Transactional
	default int markDead(long outboxId, UUID leaseToken, String errorCode, String errorMessage) {
		return markDeadRow(outboxId, requireLease(leaseToken), errorCode, errorMessage);
	}

	/**
	 * 발행 중 크래시로 lease만 남은 row를 되살린다(spec.md §7.4 "클레임 후 / 발행 전").
	 *
	 * <p>{@code attempts}가 이미 {@code maxAttempts}에 도달한 row는 {@code retry}로 되돌리지 않는다 —
	 * 되돌리면 다음 {@link #claimRows}가 {@code attempts}를 한 번 더 증가시켜
	 * {@code ck_ai_job_outbox_attempts}(0~20) CHECK를 넘길 수 있고, 넘기면 그 배치 UPDATE 전체가
	 * 롤백돼 아무 row도 발행되지 못한다. 대신 {@code dead}로 못박아 운영 증거로 남긴다(PR #255 finding 2).
	 */
	@Transactional
	@Modifying
	@Query(value = """
		UPDATE ai_job_outbox
		   SET status = CASE WHEN attempts >= :maxAttempts THEN 'dead' ELSE 'retry' END,
		       lease_token = NULL,
		       lease_until = NULL,
		       locked_by = NULL,
		       last_error_code = CASE
		           WHEN attempts >= :maxAttempts THEN 'lease_expired_exhausted'
		           ELSE last_error_code
		       END,
		       last_error_message = CASE
		           WHEN attempts >= :maxAttempts THEN 'AI job publish lease expired after exhausting retries'
		           ELSE last_error_message
		       END,
		       updated_at = now()
		 WHERE status = 'publishing'
		   AND lease_until < now()
		""", nativeQuery = true)
	int recoverExpiredLeasesRow(@Param("maxAttempts") int maxAttempts);

	@Transactional
	default int recoverExpiredLeases(int maxAttempts) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be positive: " + maxAttempts);
		}
		return recoverExpiredLeasesRow(maxAttempts);
	}

	/**
	 * 보존 삭제. {@code published}만 지운다 — {@code dead} row는 남긴다(spec.md §7.5).
	 * 한 번에 {@code batchLimit}행씩만 지우고, 호출자가 0이 될 때까지 반복한다.
	 */
	@Transactional
	@Modifying
	@Query(value = """
		DELETE FROM ai_job_outbox
		 WHERE outbox_id IN (
		         SELECT outbox_id
		           FROM ai_job_outbox
		          WHERE status = 'published'
		            AND published_at < now() - (:retentionDays * INTERVAL '1 day')
		          ORDER BY published_at
		          LIMIT :batchLimit
		       )
		""", nativeQuery = true)
	int deletePublishedOlderThanDays(
		@Param("retentionDays") int retentionDays,
		@Param("batchLimit") int batchLimit
	);

	/** 아직 발행되지 않고 남아 있는 건수. backlog 경보용(spec.md §7.1 backlog-warn-threshold). */
	@Query(value = "SELECT count(*) FROM ai_job_outbox WHERE status IN ('pending', 'retry', 'publishing')",
		nativeQuery = true)
	long countBacklog();

	private static String requireLease(UUID leaseToken) {
		if (leaseToken == null) {
			throw new IllegalArgumentException("leaseToken must not be null");
		}
		return leaseToken.toString();
	}
}
