package shinhan.fibri.ieum.main.ai.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

/**
 * 클레임·정산·lease 복구·보존 쿼리. spec.md §7.2/§7.5, Task 4 브리프.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} 배타성은 실제 Postgres 없이는 검증할 수 없다 —
 * 그래서 여기는 Testcontainers 통합 테스트다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiJobOutboxRepositoryClaimTest {

	private static final String DATABASE = "ieum_ai_job_outbox_claim";
	private static final String WORKER = "worker-1";
	private static final int MAX_ATTEMPTS = 8;

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private AiJobOutboxRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE ai_job_outbox RESTART IDENTITY CASCADE");
	}

	private long insertPending(long jobKey) {
		UUID jobId = UUID.randomUUID();
		return repository.saveAndFlush(AiJobOutbox.pending(
			jobId,
			"question_answer_dispatch",
			jobKey,
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH,
			AiJobTopology.SCHEMA_VERSION,
			"{\"schemaVersion\":1,\"jobId\":\"%s\",\"questionId\":%d}".formatted(jobId, jobKey)
		)).getId();
	}

	private Map<String, Object> row(long outboxId) {
		return jdbc.queryForMap("SELECT * FROM ai_job_outbox WHERE outbox_id = ?", outboxId);
	}

	@Test
	void claimIncrementsAttemptsAndSetsPublishingLease() {
		long outboxId = insertPending(1L);
		UUID leaseToken = UUID.randomUUID();

		List<ClaimedAiJob> claimed = repository.claim(WORKER, leaseToken, 60, 32, MAX_ATTEMPTS);

		assertThat(claimed).hasSize(1);
		ClaimedAiJob job = claimed.getFirst();
		assertThat(job.outboxId()).isEqualTo(outboxId);
		assertThat(job.attempts()).isEqualTo(1);
		assertThat(job.routingKey()).isEqualTo(AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH);
		assertThat(job.jobType()).isEqualTo("question_answer_dispatch");
		assertThat(job.jobKey()).isEqualTo(1L);
		assertThat(job.schemaVersion()).isEqualTo(AiJobTopology.SCHEMA_VERSION);
		// jsonb 는 저장 시 키 순서·공백을 정규화한다 — 바이트가 아니라 JSON 값이 같아야 한다.
		assertThat(job.payload()).contains("\"questionId\": 1").contains("\"schemaVersion\": 1");

		Map<String, Object> stored = row(outboxId);
		assertThat(stored.get("status")).isEqualTo("publishing");
		assertThat(((Number) stored.get("attempts")).intValue()).isEqualTo(1);
		assertThat(stored.get("locked_by")).isEqualTo(WORKER);
		assertThat(stored.get("lease_token")).hasToString(leaseToken.toString());
		assertThat(stored.get("lease_until")).isNotNull();
	}

	@Test
	void rowsWithFutureNextAttemptAtAreNotClaimed() {
		long outboxId = insertPending(1L);
		jdbc.update(
			"UPDATE ai_job_outbox SET status = 'retry', next_attempt_at = now() + INTERVAL '1 hour' WHERE outbox_id = ?",
			outboxId
		);

		assertThat(repository.claim(WORKER, UUID.randomUUID(), 60, 32, MAX_ATTEMPTS)).isEmpty();
		assertThat(row(outboxId).get("status")).isEqualTo("retry");
	}

	@Test
	void concurrentClaimsNeverHandOutTheSameRowTwice() throws Exception {
		int rows = 12;
		for (long jobKey = 1; jobKey <= rows; jobKey++) {
			insertPending(jobKey);
		}

		int workers = 2;
		CyclicBarrier startTogether = new CyclicBarrier(workers);
		List<Long> claimedIds = new CopyOnWriteArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int worker = 0; worker < workers; worker++) {
				String workerId = "worker-" + worker;
				results.add(pool.submit(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					List<ClaimedAiJob> claimed = repository.claim(workerId, UUID.randomUUID(), 60, rows, MAX_ATTEMPTS);
					claimed.forEach(job -> claimedIds.add(job.outboxId()));
					return claimed.size();
				}));
			}
			for (Future<Integer> result : results) {
				result.get(30, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(claimedIds).doesNotHaveDuplicates();
		assertThat(claimedIds).hasSize(rows);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM ai_job_outbox WHERE status = 'publishing' AND attempts = 1", Long.class
		)).isEqualTo((long) rows);
	}

	@Test
	void markPublishedClearsTheLeaseAndStampsPublishedAt() {
		long outboxId = insertPending(1L);
		UUID leaseToken = UUID.randomUUID();
		repository.claim(WORKER, leaseToken, 60, 32, MAX_ATTEMPTS);

		assertThat(repository.markPublished(outboxId, leaseToken)).isEqualTo(1);

		Map<String, Object> stored = row(outboxId);
		assertThat(stored.get("status")).isEqualTo("published");
		assertThat(stored.get("published_at")).isNotNull();
		assertThat(stored.get("lease_token")).isNull();
		assertThat(stored.get("lease_until")).isNull();
		assertThat(stored.get("locked_by")).isNull();
	}

	@Test
	void markRetryPushesNextAttemptAtIntoTheFutureAndRecordsTheError() {
		long outboxId = insertPending(1L);
		UUID leaseToken = UUID.randomUUID();
		repository.claim(WORKER, leaseToken, 60, 32, MAX_ATTEMPTS);

		assertThat(repository.markRetry(outboxId, leaseToken, 16, "nack", "broker nacked")).isEqualTo(1);

		Map<String, Object> stored = row(outboxId);
		assertThat(stored.get("status")).isEqualTo("retry");
		assertThat(stored.get("last_error_code")).isEqualTo("nack");
		assertThat(stored.get("lease_token")).isNull();
		assertThat(jdbc.queryForObject(
			"SELECT next_attempt_at > now() + INTERVAL '10 second' FROM ai_job_outbox WHERE outbox_id = ?",
			Boolean.class, outboxId
		)).isTrue();
	}

	@Test
	void markDeadKeepsTheRowAsOperationalEvidence() {
		long outboxId = insertPending(1L);
		UUID leaseToken = UUID.randomUUID();
		repository.claim(WORKER, leaseToken, 60, 32, MAX_ATTEMPTS);

		assertThat(repository.markDead(outboxId, leaseToken, "nack", "gave up")).isEqualTo(1);

		Map<String, Object> stored = row(outboxId);
		assertThat(stored.get("status")).isEqualTo("dead");
		assertThat(stored.get("last_error_code")).isEqualTo("nack");
		assertThat(stored.get("lease_token")).isNull();
	}

	@Test
	void settlementIsFencedByTheLeaseToken() {
		long outboxId = insertPending(1L);
		UUID leaseToken = UUID.randomUUID();
		repository.claim(WORKER, leaseToken, 60, 32, MAX_ATTEMPTS);

		UUID staleToken = UUID.randomUUID();
		assertThat(repository.markPublished(outboxId, staleToken)).isZero();
		assertThat(repository.markRetry(outboxId, staleToken, 1, "nack", "stale")).isZero();
		assertThat(repository.markDead(outboxId, staleToken, "nack", "stale")).isZero();
		assertThat(row(outboxId).get("status")).isEqualTo("publishing");
	}

	@Test
	void expiredLeaseRecoveryReturnsPublishingRowsToRetry() {
		long expired = insertPending(1L);
		long live = insertPending(2L);
		repository.claim(WORKER, UUID.randomUUID(), 60, 32, MAX_ATTEMPTS);
		jdbc.update("UPDATE ai_job_outbox SET lease_until = now() - INTERVAL '1 minute' WHERE outbox_id = ?", expired);

		assertThat(repository.recoverExpiredLeases(MAX_ATTEMPTS)).isEqualTo(1);

		Map<String, Object> recovered = row(expired);
		assertThat(recovered.get("status")).isEqualTo("retry");
		assertThat(recovered.get("lease_token")).isNull();
		assertThat(recovered.get("lease_until")).isNull();
		assertThat(recovered.get("locked_by")).isNull();
		assertThat(row(live).get("status")).isEqualTo("publishing");
	}

	@Test
	void expiredLeaseRecoveryMarksRowsAtTheAttemptCapAsDeadInsteadOfRetry() {
		// PR #255 리뷰 finding 2: claimRows 는 attempts 를 증가시킨다. 상한(maxAttempts)에 이미
		// 도달한 row 를 retry 로 되돌리면 다음 클레임에서 CHECK(attempts <= 20) 을 넘겨 배치
		// UPDATE 전체가 롤백될 수 있다 — 그래서 복구 시점에 상한 도달 row 는 dead 로 못박는다.
		int maxAttempts = 3;
		long exhausted = insertPending(1L);
		long stillRetryable = insertPending(2L);
		repository.claim(WORKER, UUID.randomUUID(), 60, 32, maxAttempts);
		jdbc.update(
			"UPDATE ai_job_outbox SET lease_until = now() - INTERVAL '1 minute', attempts = ? WHERE outbox_id = ?",
			maxAttempts, exhausted
		);
		jdbc.update(
			"UPDATE ai_job_outbox SET lease_until = now() - INTERVAL '1 minute' WHERE outbox_id = ?",
			stillRetryable
		);

		assertThat(repository.recoverExpiredLeases(maxAttempts)).isEqualTo(2);

		Map<String, Object> deadRow = row(exhausted);
		assertThat(deadRow.get("status")).isEqualTo("dead");
		assertThat(deadRow.get("lease_token")).isNull();
		assertThat(deadRow.get("lease_until")).isNull();
		assertThat(deadRow.get("locked_by")).isNull();
		assertThat(deadRow.get("last_error_code")).isEqualTo("lease_expired_exhausted");

		Map<String, Object> retryRow = row(stillRetryable);
		assertThat(retryRow.get("status")).isEqualTo("retry");
		assertThat(retryRow.get("last_error_code")).isNull();
	}

	@Test
	void claimNeverHandsOutARowAtOrAboveTheAttemptCap() {
		// claimRows 자체가 attempts >= maxAttempts 인 row 를 후보에서 제외해야 attempts 컬럼이
		// CHECK 상한을 절대 넘지 않는다(lease 복구가 dead 로 못박는 것과는 별개의 방어선).
		int maxAttempts = 2;
		long outboxId = insertPending(1L);
		jdbc.update(
			"UPDATE ai_job_outbox SET status = 'retry', attempts = ? WHERE outbox_id = ?",
			maxAttempts, outboxId
		);

		assertThat(repository.claim(WORKER, UUID.randomUUID(), 60, 32, maxAttempts)).isEmpty();
		assertThat(row(outboxId).get("status")).isEqualTo("retry");
		assertThat(((Number) row(outboxId).get("attempts")).intValue()).isEqualTo(maxAttempts);
	}

	@Test
	void retentionDeletesOldPublishedRowsAndKeepsDeadRows() {
		long oldPublished = insertPending(1L);
		long freshPublished = insertPending(2L);
		long dead = insertPending(3L);
		jdbc.update(
			"UPDATE ai_job_outbox SET status = 'published', published_at = now() - INTERVAL '30 day' WHERE outbox_id = ?",
			oldPublished
		);
		jdbc.update(
			"UPDATE ai_job_outbox SET status = 'published', published_at = now() WHERE outbox_id = ?",
			freshPublished
		);
		jdbc.update("UPDATE ai_job_outbox SET status = 'dead' WHERE outbox_id = ?", dead);

		assertThat(repository.deletePublishedOlderThanDays(7, 5000)).isEqualTo(1);

		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM ai_job_outbox WHERE outbox_id = ?", Long.class, oldPublished
		)).isZero();
		assertThat(row(freshPublished).get("status")).isEqualTo("published");
		assertThat(row(dead).get("status")).isEqualTo("dead");
	}

	@Test
	void retentionDeletesInBoundedBatches() {
		for (long jobKey = 1; jobKey <= 5; jobKey++) {
			insertPending(jobKey);
		}
		jdbc.update("UPDATE ai_job_outbox SET status = 'published', published_at = now() - INTERVAL '30 day'");

		assertThat(repository.deletePublishedOlderThanDays(7, 2)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_outbox", Long.class)).isEqualTo(3L);
	}

	@Test
	void backlogCountsOnlyRowsWaitingToBePublished() {
		insertPending(1L);
		long published = insertPending(2L);
		jdbc.update(
			"UPDATE ai_job_outbox SET status = 'published', published_at = now() WHERE outbox_id = ?", published
		);

		assertThat(repository.countBacklog()).isEqualTo(1L);
	}
}
