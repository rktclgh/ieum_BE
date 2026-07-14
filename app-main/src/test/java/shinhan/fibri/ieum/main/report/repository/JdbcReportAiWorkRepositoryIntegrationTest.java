package shinhan.fibri.ieum.main.report.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcReportAiWorkRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_report_ai_work";
	private static final int MAX_ATTEMPTS = 5;

	private JdbcClient jdbc;
	private ReportAiWorkRepository repository;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		repository = new JdbcReportAiWorkRepository(jdbc);
		seedUsersAndRoom();
	}

	@Test
	void claimsOldestDueReportWithAttemptLeaseAndImmutableContext() {
		long olderReportId = insertDueReport(100L, OffsetDateTime.parse("2026-07-11T00:00:00Z"));
		insertDueReport(101L, OffsetDateTime.parse("2026-07-11T00:01:00Z"));

		ClaimedReport claimed = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(claimed.reportId()).isEqualTo(olderReportId);
		assertThat(claimed.reportedMessageId()).isEqualTo(100L);
		assertThat(claimed.reporterId()).isEqualTo(1L);
		assertThat(claimed.reportedUserId()).isEqualTo(2L);
		assertThat(claimed.reason()).isEqualTo(ReportReason.abuse);
		assertThat(claimed.contextSnapshot()).isEqualTo("{\"reported\": {\"messageId\": 100}, \"schemaVersion\": 1}");
		assertThat(claimed.contextHash()).isEqualTo("a".repeat(64));
		assertThat(claimed.attempts()).isEqualTo(1);

		var row = jdbc.sql("""
			SELECT ai_review_state::text, ai_review_attempt_id, ai_attempts, ai_locked_by
			FROM reports
			WHERE report_id = :reportId
			""").param("reportId", olderReportId).query().singleRow();
		assertThat(row.get("ai_review_state")).isEqualTo("processing");
		assertThat(row.get("ai_review_attempt_id")).isEqualTo(claimed.attemptId());
		assertThat(((Number) row.get("ai_attempts")).intValue()).isEqualTo(1);
		assertThat(row.get("ai_locked_by")).isEqualTo("worker-a");
		OffsetDateTime storedLeaseUntil = jdbc.sql("SELECT ai_lease_until FROM reports WHERE report_id = :reportId")
			.param("reportId", olderReportId)
			.query((rs, rowNumber) -> rs.getObject("ai_lease_until", OffsetDateTime.class))
			.single();
		assertThat(storedLeaseUntil).isEqualTo(claimed.leaseUntil());
	}

	@Test
	void claimsMessageWorkWhileManualAnswerReportRemainsCancelled() {
		long answerId = seedQuestionAndAiAnswer();
		long answerReportId = insertCancelledAnswerReport(answerId);
		long messageReportId = insertDueReport(100L, OffsetDateTime.parse("2026-07-11T00:00:00Z"));

		ClaimedReport claimed = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(claimed.reportId()).isEqualTo(messageReportId);
		assertThat(workState(answerReportId)).isEqualTo("cancelled");
		assertThat(repository.claimNext("worker-b", Duration.ofMinutes(2), MAX_ATTEMPTS)).isEmpty();
	}

	@Test
	void doesNotClaimTheSameDueReportForTwoConcurrentWorkers() throws Exception {
		insertDueReport(100L, OffsetDateTime.parse("2026-07-11T00:00:00Z"));
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Optional<ClaimedReport>> first = executor.submit(claimAfterStart("worker-a", ready, start));
			Future<Optional<ClaimedReport>> second = executor.submit(claimAfterStart("worker-b", ready, start));
			ready.await();
			start.countDown();

			List<ClaimedReport> claims = List.of(first.get(), second.get()).stream()
				.flatMap(Optional::stream)
				.toList();

			assertThat(claims).hasSize(1);
			assertThat(claims.getFirst().attempts()).isEqualTo(1);
		}
	}

	@Test
	void marksCurrentAttemptForRetryAndDoesNotClaimItBeforeItsDueTime() {
		insertDueReport(100L, OffsetDateTime.parse("2026-07-11T00:00:00Z"));
		ClaimedReport claimed = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS).orElseThrow();
		OffsetDateTime nextAttemptAt = OffsetDateTime.now().plusMinutes(5);

		assertThat(repository.markRetry(
			claimed.reportId(), claimed.attemptId(), nextAttemptAt, "AI_TIMEOUT", "AI service timed out"
		)).isTrue();
		assertThat(workState(claimed.reportId())).isEqualTo("retry");
		assertThat(repository.claimNext("worker-b", Duration.ofMinutes(2), MAX_ATTEMPTS)).isEmpty();
	}

	@Test
	void rejectsStaleAttemptTransitionsAfterTheReportIsReclaimed() {
		insertDueReport(100L, OffsetDateTime.parse("2026-07-11T00:00:00Z"));
		ClaimedReport first = repository.claimNext("worker-a", Duration.ofMinutes(2), MAX_ATTEMPTS).orElseThrow();
		assertThat(repository.markRetry(
			first.reportId(), first.attemptId(), OffsetDateTime.now().minusSeconds(1), "AI_TIMEOUT", "retry"
		)).isTrue();
		ClaimedReport second = repository.claimNext("worker-b", Duration.ofMinutes(2), MAX_ATTEMPTS).orElseThrow();

		assertThat(repository.markRetry(
			first.reportId(), first.attemptId(), OffsetDateTime.now().plusMinutes(1), "STALE", "stale retry"
		)).isFalse();
		assertThat(repository.markDead(first.reportId(), first.attemptId(), "STALE", "stale dead")).isFalse();
		assertThat(repository.markDead(second.reportId(), second.attemptId(), "INVALID_RESPONSE", "invalid response")).isTrue();
		assertThat(workState(second.reportId())).isEqualTo("dead");
	}

	@Test
	void recoversAnExpiredFifthAttemptAsDeadWork() {
		insertDueReport(100L, OffsetDateTime.parse("2026-07-11T00:00:00Z"));
		ClaimedReport claimed = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			claimed = repository.claimNext("worker-a", Duration.ofSeconds(1), MAX_ATTEMPTS).orElseThrow();
			if (attempt < MAX_ATTEMPTS) {
				assertThat(repository.markRetry(
					claimed.reportId(), claimed.attemptId(), OffsetDateTime.now().minusSeconds(1), "AI_TIMEOUT", "retry"
				)).isTrue();
			}
		}

		assertThat(repository.recoverExpiredLeases(OffsetDateTime.now().plusMinutes(1), MAX_ATTEMPTS)).isEqualTo(1);
		assertThat(workState(claimed.reportId())).isEqualTo("dead");
		assertThat(repository.claimNext("worker-b", Duration.ofMinutes(2), MAX_ATTEMPTS)).isEmpty();
	}

	@Test
	void rejectsALeaseShorterThanOneSecond() {
		assertThatThrownBy(() -> repository.claimNext("worker-a", Duration.ofMillis(999), MAX_ATTEMPTS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least one second");
	}

	private Callable<Optional<ClaimedReport>> claimAfterStart(
		String workerId,
		CountDownLatch ready,
		CountDownLatch start
	) {
		return () -> {
			ready.countDown();
			start.await();
			return new JdbcReportAiWorkRepository(
				JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE))
			).claimNext(workerId, Duration.ofMinutes(2), MAX_ATTEMPTS);
		};
	}

	private String workState(long reportId) {
		return jdbc.sql("SELECT ai_review_state::text FROM reports WHERE report_id = :reportId")
			.param("reportId", reportId)
			.query(String.class)
			.single();
	}

	private void seedUsersAndRoom() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status, created_at, updated_at)
			VALUES
			    (1, 'reporter@example.com', 'email', 'hash', 'reporter', true, 'user', 'active', '2026-07-11T00:00:00Z', '2026-07-11T00:00:00Z'),
			    (2, 'reported@example.com', 'email', 'hash', 'reported', true, 'user', 'active', '2026-07-11T00:00:00Z', '2026-07-11T00:00:00Z')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key, created_at)
			VALUES (10, 'direct', 'd:1:2', '2026-07-11T00:00:00Z')
			""").update();
		jdbc.sql("""
			INSERT INTO messages (message_id, room_id, sender_id, content, created_at)
			VALUES (100, 10, 2, 'reported message', '2026-07-11T00:00:00Z'),
			       (101, 10, 2, 'next reported message', '2026-07-11T00:01:00Z')
			""").update();
	}

	private long insertDueReport(long messageId, OffsetDateTime createdAt) {
		return jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, message_id, reported_user_id, reason, detail, context_snapshot, context_hash,
			    status, ai_review_state, ai_attempts, ai_next_attempt_at, created_at
			)
			VALUES (
			    1, :messageId, 2, 'abuse', 'detail', '{"schemaVersion":1,"reported":{"messageId":100}}'::jsonb, :contextHash,
			    'pending', 'pending', 0, :dueAt, :createdAt
			)
			RETURNING report_id
			""")
			.param("messageId", messageId)
			.param("contextHash", "a".repeat(64))
			.param("dueAt", createdAt.minusMinutes(1))
			.param("createdAt", createdAt)
			.query(Long.class)
			.single();
	}

	private long seedQuestionAndAiAnswer() {
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (1, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울')
			RETURNING pin_id
			""").query(Long.class).single();
		long questionId = jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, 1, 'question', 'question content')
			RETURNING question_id
			""").param("pinId", pinId).query(Long.class).single();
		return jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (:questionId, NULL, TRUE, 'AI answer')
			RETURNING answer_id
			""").param("questionId", questionId).query(Long.class).single();
	}

	private long insertCancelledAnswerReport(long answerId) {
		return jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, target_type, answer_id, reported_user_id, reason, context_snapshot, context_hash,
			    status, ai_review_state, ai_attempts, ai_next_attempt_at
			)
			VALUES (
			    1, 'answer', :answerId, NULL, 'abuse', '{"targetType":"answer"}'::jsonb, :contextHash,
			    'pending', 'cancelled', 0, NULL
			)
			RETURNING report_id
			""")
			.param("answerId", answerId)
			.param("contextHash", "b".repeat(64))
			.query(Long.class)
			.single();
	}
}
