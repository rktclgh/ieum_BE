package shinhan.fibri.ieum.ai.question.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import javax.sql.DataSource;
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

	private DataSource dataSource;
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
		dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);
		repository = new JdbcQuestionTaskWorkRepository(jdbc);
	}

	@Test
	void claimsTheOldestDueActiveTaskWithANewLeaseToken() {
		long olderQuestionId = insertQuestion(false, false);
		long newerQuestionId = insertQuestion(false, false);
		insertTask(olderQuestionId, OffsetDateTime.parse("2026-07-01T00:00:00Z"), 0, OffsetDateTime.parse("2026-07-01T00:00:00Z"));
		insertTask(newerQuestionId, OffsetDateTime.parse("2026-07-01T00:01:00Z"), 0, OffsetDateTime.parse("2026-07-01T00:00:00Z"));

		ClaimedQuestionTask claimed = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(claimed.questionId()).isEqualTo(olderQuestionId);
		assertThat(claimed.leaseToken()).isNotNull();
		assertThat(claimed.attempts()).isEqualTo(1);
		assertThat(claimed.leaseUntil()).isAfter(OffsetDateTime.now().plusMinutes(1));
		var row = jdbc.sql("""
			SELECT status::text, stage::text, attempts, locked_by, lease_token
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""").param("questionId", olderQuestionId).query().singleRow();
		assertThat(row.get("status")).isEqualTo("processing");
		assertThat(row.get("stage")).isEqualTo("analyzing");
		assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
		assertThat(row.get("locked_by")).isEqualTo("worker-a");
		assertThat(row.get("lease_token")).isEqualTo(claimed.leaseToken());
		OffsetDateTime storedLeaseUntil = jdbc.sql("""
			SELECT lease_until
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", olderQuestionId)
			.query((resultSet, rowNumber) -> resultSet.getObject("lease_until", OffsetDateTime.class))
			.single();
		assertThat(storedLeaseUntil).isEqualTo(claimed.leaseUntil());
	}

	@Test
	void doesNotClaimTasksWhoseQuestionOrPinWasDeleted() {
		long deletedQuestionId = insertQuestion(true, false);
		long deletedPinQuestionId = insertQuestion(false, true);
		long activeQuestionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(deletedQuestionId, dueAt.minusMinutes(2), 0, dueAt);
		insertTask(deletedPinQuestionId, dueAt.minusMinutes(1), 0, dueAt);
		insertTask(activeQuestionId, dueAt, 0, dueAt);

		ClaimedQuestionTask claimed = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(claimed.questionId()).isEqualTo(activeQuestionId);
	}

	@Test
	void doesNotClaimFutureOrExhaustedTasks() {
		long futureQuestionId = insertQuestion(false, false);
		long exhaustedQuestionId = insertQuestion(false, false);
		insertTask(futureQuestionId, OffsetDateTime.parse("2026-07-01T00:00:00Z"), 0, OffsetDateTime.now().plusMinutes(5));
		insertTask(exhaustedQuestionId, OffsetDateTime.parse("2026-07-01T00:01:00Z"), MAX_ATTEMPTS, OffsetDateTime.parse("2026-07-01T00:00:00Z"));

		assertThat(repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)).isEmpty();
	}

	@Test
	void doesNotClaimTheSameTaskForTwoConcurrentWorkers() throws Exception {
		long questionId = insertQuestion(false, false);
		insertTask(questionId, OffsetDateTime.parse("2026-07-01T00:00:00Z"), 0, OffsetDateTime.parse("2026-07-01T00:00:00Z"));
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Optional<ClaimedQuestionTask>> first = executor.submit(claimAfterStart("worker-a", ready, start));
			Future<Optional<ClaimedQuestionTask>> second = executor.submit(claimAfterStart("worker-b", ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<ClaimedQuestionTask> claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
				.stream()
				.flatMap(Optional::stream)
				.toList();
			assertThat(claims).singleElement().satisfies(claimed -> {
				assertThat(claimed.questionId()).isEqualTo(questionId);
				assertThat(claimed.attempts()).isEqualTo(1);
			});
		}
	}

	@Test
	void skipsALockedOldestTaskAndClaimsTheNextDueTask() throws Exception {
		long oldestQuestionId = insertQuestion(false, false);
		long nextQuestionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(oldestQuestionId, dueAt, 0, dueAt);
		insertTask(nextQuestionId, dueAt.plusMinutes(1), 0, dueAt);

		try (Connection lockConnection = dataSource.getConnection()) {
			lockConnection.setAutoCommit(false);
			try (var statement = lockConnection.prepareStatement("""
				SELECT 1
				FROM ai_question_tasks
				WHERE question_id = ?
				FOR UPDATE
				""")) {
				statement.setLong(1, oldestQuestionId);
				statement.executeQuery();
			}

			ClaimedQuestionTask claimed = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)
				.orElseThrow();

			assertThat(claimed.questionId()).isEqualTo(nextQuestionId);
			lockConnection.rollback();
		}
	}

	@Test
	void marksOnlyTheCurrentLeaseForRetryAndRespectsTheNextAttemptTime() {
		long questionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(questionId, dueAt, 0, dueAt);
		ClaimedQuestionTask claimed = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)
			.orElseThrow();
		OffsetDateTime nextAttemptAt = OffsetDateTime.now().plusMinutes(5);

		assertThat(repository.markRetry(
			questionId, "worker-a", claimed.leaseToken(), nextAttemptAt, "MODEL_TIMEOUT", "model timed out"
		)).isTrue();
		var row = jdbc.sql("""
			SELECT status::text, lease_until, locked_by, lease_token, next_attempt_at, last_error_code
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""").param("questionId", questionId).query().singleRow();
		assertThat(row.get("status")).isEqualTo("retry");
		assertThat(row.get("lease_until")).isNull();
		assertThat(row.get("locked_by")).isNull();
		assertThat(row.get("lease_token")).isNull();
		assertThat(row.get("last_error_code")).isEqualTo("MODEL_TIMEOUT");
		OffsetDateTime storedNextAttemptAt = jdbc.sql("""
			SELECT next_attempt_at
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query((resultSet, rowNumber) -> resultSet.getObject("next_attempt_at", OffsetDateTime.class))
			.single();
		assertThat(storedNextAttemptAt).isEqualTo(nextAttemptAt);
		assertThat(repository.claimNext("worker-b", Duration.ofMinutes(2), MAX_ATTEMPTS)).isEmpty();
	}

	@Test
	void reclaimsARetryTaskWhenItsNextAttemptIsDue() {
		long questionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(questionId, dueAt, 0, dueAt);
		ClaimedQuestionTask first = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(repository.markRetry(
			questionId, "worker-a", first.leaseToken(), OffsetDateTime.now().minusSeconds(1), "MODEL_TIMEOUT", "model timed out"
		)).isTrue();
		ClaimedQuestionTask second = repository.claimNext("worker-b", Duration.ofMinutes(2), MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(second.questionId()).isEqualTo(questionId);
		assertThat(second.attempts()).isEqualTo(2);
		assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
	}

	@Test
	void rejectsRetryForAnotherLeaseTokenOrAnExpiredLease() {
		long questionId = insertQuestion(false, false);
		OffsetDateTime dueAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		insertTask(questionId, dueAt, 0, dueAt);
		ClaimedQuestionTask claimed = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(repository.markRetry(
			questionId, "worker-a", UUID.randomUUID(), OffsetDateTime.now(), "MODEL_TIMEOUT", "model timed out"
		)).isFalse();
		assertThat(repository.markRetry(
			questionId, "worker-b", claimed.leaseToken(), OffsetDateTime.now(), "MODEL_TIMEOUT", "model timed out"
		)).isFalse();
		jdbc.sql("""
			UPDATE ai_question_tasks
			SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
			WHERE question_id = :questionId
			""").param("questionId", questionId).update();
		assertThat(repository.markRetry(
			questionId, "worker-a", claimed.leaseToken(), OffsetDateTime.now(), "MODEL_TIMEOUT", "model timed out"
		)).isFalse();
		long pendingQuestionId = insertQuestion(false, false);
		insertTask(pendingQuestionId, dueAt, 0, dueAt);
		assertThat(repository.markRetry(
			pendingQuestionId, "worker-a", claimed.leaseToken(), OffsetDateTime.now(), "MODEL_TIMEOUT", "model timed out"
		)).isFalse();
	}

	private Callable<Optional<ClaimedQuestionTask>> claimAfterStart(
		String workerId,
		CountDownLatch ready,
		CountDownLatch start
	) {
		return () -> {
			ready.countDown();
			assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
			return new JdbcQuestionTaskWorkRepository(
				JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE))
			).claimNext(workerId, Duration.ofMinutes(2), MAX_ATTEMPTS);
		};
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
