package shinhan.fibri.ieum.ai.question.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcQuestionTaskWorkRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_ai_question_claim";
	private static final int MAX_ATTEMPTS = 5;
	private static final AtomicLong SEQUENCE = new AtomicLong();

	private JdbcClient jdbc;
	private QuestionTaskWorkRepository repository;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"))
			.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)")
			.update();
	}

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		repository = new JdbcQuestionTaskWorkRepository(jdbc);
	}

	@Test
	void claimsOnlyTheRequestedQuestionIdInsteadOfTheGlobalOldestTask() {
		long oldestQuestionId = insertQuestion(false, false);
		long requestedQuestionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(oldestQuestionId, dueAt.minusMinutes(1), 0, dueAt);
		insertTask(requestedQuestionId, dueAt, 0, dueAt);

		ClaimedQuestionTask claimed = repository.claimByQuestionId(
			requestedQuestionId, "worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS
		).orElseThrow();

		assertThat(claimed.questionId()).isEqualTo(requestedQuestionId);
		assertThat(taskStatus(oldestQuestionId)).isEqualTo("pending");
		assertThat(taskStatus(requestedQuestionId)).isEqualTo("processing");
		assertThat(claimed.leaseToken()).isNotNull();
		assertThat(claimed.attempts()).isEqualTo(1);
		assertThat(claimed.leaseUntil()).isAfter(OffsetDateTime.now().plusMinutes(1));
	}

	@Test
	void allowsOnlyOneConcurrentClaimForTheSameQuestionId() throws Exception {
		long questionId = insertQuestion(false, false);
		insertTask(questionId, OffsetDateTime.parse("2026-07-01T00:00:00Z"), 0,
			OffsetDateTime.parse("2026-07-01T00:00:00Z"));
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Optional<ClaimedQuestionTask>> first = executor.submit(
				claimAfterStart(questionId, "worker-a", ready, start)
			);
			Future<Optional<ClaimedQuestionTask>> second = executor.submit(
				claimAfterStart(questionId, "worker-b", ready, start)
			);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<ClaimedQuestionTask> claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
				.stream()
				.flatMap(Optional::stream)
				.toList();

			assertThat(claims).singleElement().satisfies(claim -> {
				assertThat(claim.questionId()).isEqualTo(questionId);
				assertThat(claim.attempts()).isEqualTo(1);
			});
		}
	}

	@Test
	void reclaimsOnlyAnExpiredProcessingLeaseWithANewFence() {
		long questionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(questionId, dueAt, 0, dueAt);
		ClaimedQuestionTask first = repository.claimByQuestionId(
			questionId, "worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS
		).orElseThrow();

		assertThat(repository.claimByQuestionId(
			questionId, "worker-b", Duration.ofMinutes(2), MAX_ATTEMPTS
		)).isEmpty();
		jdbc.sql("""
			UPDATE ai_question_tasks
			SET lease_until = :expiredAt
			WHERE question_id = :questionId
			""")
			.param("expiredAt", OffsetDateTime.now().minusSeconds(1))
			.param("questionId", questionId)
			.update();

		ClaimedQuestionTask reclaimed = repository.claimByQuestionId(
			questionId, "worker-b", Duration.ofMinutes(2), MAX_ATTEMPTS
		).orElseThrow();

		assertThat(reclaimed.questionId()).isEqualTo(questionId);
		assertThat(reclaimed.attempts()).isEqualTo(2);
		assertThat(reclaimed.leaseToken()).isNotEqualTo(first.leaseToken());
	}

	@Test
	void doesNotClaimFutureExhaustedCancelledOrDeletedTasks() {
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		long futureId = insertQuestion(false, false);
		long exhaustedId = insertQuestion(false, false);
		long cancelledId = insertQuestion(false, false);
		long deletedQuestionId = insertQuestion(true, false);
		long deletedPinId = insertQuestion(false, true);
		insertTask(futureId, dueAt, 0, OffsetDateTime.now().plusMinutes(5));
		insertTask(exhaustedId, dueAt, MAX_ATTEMPTS, dueAt);
		insertTask(cancelledId, dueAt, 0, dueAt);
		insertTask(deletedQuestionId, dueAt, 0, dueAt);
		insertTask(deletedPinId, dueAt, 0, dueAt);
		jdbc.sql("UPDATE ai_question_tasks SET cancel_requested_at = now() WHERE question_id = :questionId")
			.param("questionId", cancelledId)
			.update();

		for (long questionId : List.of(futureId, exhaustedId, cancelledId, deletedQuestionId, deletedPinId)) {
			assertThat(repository.claimByQuestionId(
				questionId, "worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS
			)).isEmpty();
		}
	}

	@Test
	void findsAtMostThirtyTwoDueTicketIdsIncludingExpiredLeasesWithoutDiscoveringQuestions() {
		OffsetDateTime now = OffsetDateTime.now();
		long expiredId = insertQuestion(false, false);
		insertTask(expiredId, now.minusMinutes(2), 0, now.minusMinutes(2));
		repository.claimByQuestionId(expiredId, "worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS).orElseThrow();
		jdbc.sql("UPDATE ai_question_tasks SET lease_until = :expiredAt WHERE question_id = :questionId")
			.param("expiredAt", now.minusSeconds(1))
			.param("questionId", expiredId)
			.update();
		for (int index = 0; index < 35; index++) {
			long questionId = insertQuestion(false, false);
			insertTask(questionId, now.minusMinutes(1).plusSeconds(index), 0, now.minusSeconds(1));
		}
		insertQuestion(false, false);

		List<Long> dueIds = repository.findDueQuestionIds(MAX_ATTEMPTS, 32);

		assertThat(dueIds).hasSize(32).contains(expiredId);
		assertThat(dueIds).allMatch(this::taskExists);
	}

	@Test
	void cleanupOfCancelledOrDeletedTicketsIsBoundedAndRemovesThemFromRecovery() {
		OffsetDateTime now = OffsetDateTime.now();
		long requestedCancellationId = insertQuestion(false, false);
		long deletedQuestionId = insertQuestion(true, false);
		long deletedPinId = insertQuestion(false, true);
		long activeId = insertQuestion(false, false);
		for (long id : List.of(requestedCancellationId, deletedQuestionId, deletedPinId, activeId)) {
			insertTask(id, now.minusMinutes(1), 0, now.minusSeconds(1));
		}
		jdbc.sql("UPDATE ai_question_tasks SET cancel_requested_at = :now WHERE question_id = :questionId")
			.param("now", now)
			.param("questionId", requestedCancellationId)
			.update();

		assertThat(repository.cleanupCancelledOrDeleted(2)).isEqualTo(2);
		assertThat(repository.findDueQuestionIds(MAX_ATTEMPTS, 32)).containsExactly(activeId);
		assertThat(repository.cleanupCancelledOrDeleted(2)).isEqualTo(1);
		assertThat(cancelledTaskCount()).isEqualTo(3);
		assertThat(taskStatus(activeId)).isEqualTo("pending");
	}

	@Test
	void marksRetryOnlyForTheCurrentUnexpiredLeaseFence() {
		long questionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(questionId, dueAt, 0, dueAt);
		ClaimedQuestionTask claimed = repository.claimByQuestionId(
			questionId, "worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS
		).orElseThrow();

		assertThat(repository.markRetry(
			questionId,
			"worker-a",
			UUID.randomUUID(),
			Duration.ofSeconds(10)
		)).isFalse();
		assertThat(repository.markRetry(
			questionId,
			"worker-a",
			claimed.leaseToken(),
			Duration.ofSeconds(10)
		)).isTrue();
		var row = jdbc.sql("""
			SELECT status::text, last_error_code, last_error_message
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query().singleRow();
		assertThat(row.get("status")).isEqualTo("retry");
		assertThat(row.get("last_error_code")).isEqualTo("QUESTION_ANSWER_PROCESSING_FAILED");
		assertThat(row.get("last_error_message")).isEqualTo("Question answer processing failed");
		OffsetDateTime nextAttemptAt = jdbc.sql("""
			SELECT next_attempt_at
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query((resultSet, rowNumber) -> resultSet.getObject("next_attempt_at", OffsetDateTime.class))
			.single();
		assertThat(nextAttemptAt)
			.isAfter(OffsetDateTime.now().plusSeconds(8))
			.isBefore(OffsetDateTime.now().plusSeconds(12));
	}

	@Test
	void dispatchSnapshotComputesAnActiveLeaseWithTheDatabaseClock() {
		long questionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(questionId, dueAt, 0, dueAt);
		repository.claimByQuestionId(
			questionId, "worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS
		).orElseThrow();

		assertThat(repository.findDispatchSnapshot(questionId).orElseThrow().activeLease()).isTrue();

		jdbc.sql("""
			UPDATE ai_question_tasks
			SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.update();

		assertThat(repository.findDispatchSnapshot(questionId).orElseThrow().activeLease()).isFalse();
	}

	@Test
	void marksDeadOnlyForTheCurrentFenceAndClearsLeaseOwnership() {
		long questionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(questionId, dueAt, 4, dueAt);
		ClaimedQuestionTask claimed = repository.claimByQuestionId(
			questionId, "worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS
		).orElseThrow();

		assertThat(repository.markDead(questionId, "worker-a", UUID.randomUUID())).isFalse();
		assertThat(repository.markDead(questionId, "worker-a", claimed.leaseToken())).isTrue();

		var row = jdbc.sql("""
			SELECT status::text, lease_until, locked_by, lease_token, last_error_code, last_error_message
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query().singleRow();
		assertThat(row.get("status")).isEqualTo("dead");
		assertThat(row.get("lease_until")).isNull();
		assertThat(row.get("locked_by")).isNull();
		assertThat(row.get("lease_token")).isNull();
		assertThat(row.get("last_error_code")).isEqualTo("QUESTION_ANSWER_PROCESSING_FAILED");
		assertThat(row.get("last_error_message")).isEqualTo("Question answer processing failed");
	}

	@Test
	void recoveryMarksDueAndExpiredExhaustedTasksDeadInBoundedBatches() {
		OffsetDateTime dueAt = OffsetDateTime.now().minusMinutes(5);
		long pendingId = insertQuestion(false, false);
		long expiredProcessingId = insertQuestion(false, false);
		long futureId = insertQuestion(false, false);
		insertTask(pendingId, dueAt, MAX_ATTEMPTS, dueAt);
		insertTask(expiredProcessingId, dueAt.plusSeconds(1), 4, dueAt);
		repository.claimByQuestionId(
			expiredProcessingId, "worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS
		).orElseThrow();
		jdbc.sql("""
			UPDATE ai_question_tasks
			SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
			WHERE question_id = :questionId
			""")
			.param("questionId", expiredProcessingId)
			.update();
		insertTask(futureId, dueAt.plusSeconds(2), MAX_ATTEMPTS, OffsetDateTime.now().plusMinutes(5));

		assertThat(repository.markExhaustedDueTasksDead(MAX_ATTEMPTS, 1)).isEqualTo(1);
		assertThat(deadTaskCount()).isEqualTo(1);
		assertThat(repository.markExhaustedDueTasksDead(MAX_ATTEMPTS, 1)).isEqualTo(1);

		assertThat(taskStatus(pendingId)).isEqualTo("dead");
		assertThat(taskStatus(expiredProcessingId)).isEqualTo("dead");
		assertThat(taskStatus(futureId)).isEqualTo("pending");
		assertThat(deadTasksRetainLeaseOwnership()).isFalse();
	}

	@Test
	void dueRecoveryUsesTheConfiguredMaximumAttemptsInsteadOfFive() {
		long questionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.now().minusMinutes(1);
		insertTask(questionId, dueAt, 3, dueAt);

		assertThat(repository.findDueQuestionIds(3, 32)).doesNotContain(questionId);
		assertThat(repository.findDueQuestionIds(4, 32)).contains(questionId);
	}

	private Callable<Optional<ClaimedQuestionTask>> claimAfterStart(
		long questionId,
		String workerId,
		CountDownLatch ready,
		CountDownLatch start
	) {
		return () -> {
			ready.countDown();
			assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
			return new JdbcQuestionTaskWorkRepository(
				JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE))
			).claimByQuestionId(questionId, workerId, Duration.ofMinutes(2), MAX_ATTEMPTS);
		};
	}

	private String taskStatus(long questionId) {
		return jdbc.sql("SELECT status::text FROM ai_question_tasks WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(String.class)
			.single();
	}

	private boolean taskExists(long questionId) {
		return jdbc.sql("SELECT count(*) FROM ai_question_tasks WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(Integer.class)
			.single() == 1;
	}

	private int cancelledTaskCount() {
		return jdbc.sql("SELECT count(*) FROM ai_question_tasks WHERE status = 'cancelled'")
			.query(Integer.class)
			.single();
	}

	private int deadTaskCount() {
		return jdbc.sql("SELECT count(*) FROM ai_question_tasks WHERE status = 'dead'")
			.query(Integer.class)
			.single();
	}

	private boolean deadTasksRetainLeaseOwnership() {
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM ai_question_tasks
			    WHERE status = 'dead'
			      AND (lease_until IS NOT NULL OR locked_by IS NOT NULL OR lease_token IS NOT NULL)
			)
			""")
			.query(Boolean.class)
			.single();
	}

	private long insertQuestion(boolean deletedQuestion, boolean deletedPin) {
		long sequence = SEQUENCE.incrementAndGet();
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "claim-" + sequence + "@example.com")
			.param("nickname", "claim-" + sequence)
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address, deleted_at)
			VALUES (
				:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul',
				CASE WHEN :deletedPin THEN now() ELSE NULL END
			)
			RETURNING pin_id
			""")
			.param("userId", userId)
			.param("deletedPin", deletedPin)
			.query(Long.class)
			.single();
		return jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content, deleted_at)
			VALUES (
				:pinId, :userId, 'question title', 'question content',
				CASE WHEN :deletedQuestion THEN now() ELSE NULL END
			)
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.param("deletedQuestion", deletedQuestion)
			.query(Long.class)
			.single();
	}

	private void insertTask(long questionId, OffsetDateTime createdAt, int attempts, OffsetDateTime nextAttemptAt) {
		jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id, attempts, next_attempt_at, created_at)
			VALUES (:questionId, :attempts, :nextAttemptAt, :createdAt)
			""")
			.param("questionId", questionId)
			.param("attempts", attempts)
			.param("nextAttemptAt", nextAttemptAt)
			.param("createdAt", createdAt)
			.update();
	}
}
