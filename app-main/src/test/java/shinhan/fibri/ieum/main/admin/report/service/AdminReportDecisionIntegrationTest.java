package shinhan.fibri.ieum.main.admin.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportAlreadyResolvedException;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportNotFoundException;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository;
import shinhan.fibri.ieum.main.report.repository.JdbcReportAiWorkRepository;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AdminReportDecisionIntegrationTest {

	private static final String DATABASE = "ieum_main_admin_report_decision";
	private static AdminReportDecisionService service;
	private static JdbcReportAiWorkRepository aiWorkRepository;
	private static TransactionTemplate transaction;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void setUpDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		var dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = new JdbcTemplate(dataSource);
		var repository = new AdminReportRepository(new NamedParameterJdbcTemplate(dataSource));
		service = new AdminReportDecisionService(repository);
		aiWorkRepository = new JdbcReportAiWorkRepository(JdbcClient.create(dataSource));
		transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		jdbc.execute("ALTER TABLE reports DISABLE TRIGGER trg_reports_target_integrity");
	}

	@BeforeEach
	void resetRows() {
		jdbc.execute("TRUNCATE TABLE users CASCADE");
		jdbc.update("""
			INSERT INTO users(user_id, email, password_hash, nickname, role, status)
			VALUES
				(1, 'reporter@example.com', 'hash', 'reporter', 'user', 'active'),
				(2, 'reported@example.com', 'hash', 'reported', 'user', 'suspended'),
				(8, 'admin8@example.com', 'hash', 'admin8', 'admin', 'active'),
				(9, 'admin9@example.com', 'hash', 'admin9', 'admin', 'active')
			""");
	}

	@ParameterizedTest
	@ValueSource(strings = {"pending", "processing", "retry"})
	void confirmResolvesAndAtomicallyFencesEveryLiveAiWorkState(String aiState) {
		UUID attemptId = insertMessageReport(10L, 2L, "pending", aiState, null, null);

		inTransaction(() -> service.confirm(10L, 9L));

		var row = jdbc.queryForMap("""
			SELECT status::text AS status, resolved_by, resolved_at, ai_review_state::text AS ai_state,
			       ai_review_attempt_id, ai_next_attempt_at, ai_lease_until, ai_locked_by
			FROM reports WHERE report_id = 10
			""");
		assertThat(row.get("status")).isEqualTo("confirmed");
		assertThat(row.get("resolved_by")).isEqualTo(9L);
		assertThat(row.get("resolved_at")).isNotNull();
		assertThat(row.get("ai_state")).isEqualTo("cancelled");
		assertThat(row.get("ai_review_attempt_id")).isNull();
		assertThat(row.get("ai_next_attempt_at")).isNull();
		assertThat(row.get("ai_lease_until")).isNull();
		assertThat(row.get("ai_locked_by")).isNull();
		assertThat(count("SELECT COUNT(*) FROM user_sanctions")).isZero();
		if (attemptId != null) {
			assertThat(aiWorkRepository.markRetry(
				10L,
				attemptId,
				OffsetDateTime.now().plusMinutes(5),
				"STALE",
				"stale worker"
			)).isFalse();
		}
	}

	@Test
	void aiReviewedReportCanBeConfirmedWithoutChangingCompletedAiReview() {
		insertMessageReport(10L, 2L, "ai_reviewed", "cancelled", null, null);
		jdbc.update("""
			UPDATE reports
			SET ai_review_state = 'completed',
			    ai_decision = 'normal',
			    ai_review_result = '{"category":"normal"}'::jsonb,
			    ai_reviewed_at = CURRENT_TIMESTAMP
			WHERE report_id = 10
			""");

		inTransaction(() -> service.confirm(10L, 9L));

		assertThat(value("SELECT status::text FROM reports WHERE report_id = 10")).isEqualTo("confirmed");
		assertThat(value("SELECT ai_review_state::text FROM reports WHERE report_id = 10")).isEqualTo("completed");
		assertThat(value("SELECT ai_decision::text FROM reports WHERE report_id = 10")).isEqualTo("normal");
	}

	@Test
	void sameDecisionIsIdempotentAndPreservesOriginalResolverAndTimestamp() {
		OffsetDateTime original = OffsetDateTime.parse("2026-07-14T01:02:03Z");
		insertMessageReport(10L, 2L, "confirmed", "retry", 8L, original);

		inTransaction(() -> service.confirm(10L, 9L));

		assertThat(jdbc.queryForObject("SELECT resolved_by FROM reports WHERE report_id = 10", Long.class))
			.isEqualTo(8L);
		assertThat(jdbc.queryForObject("SELECT resolved_at FROM reports WHERE report_id = 10", OffsetDateTime.class))
			.isEqualTo(original);
		assertThat(value("SELECT ai_review_state::text FROM reports WHERE report_id = 10")).isEqualTo("cancelled");
	}

	@Test
	void oppositeFinalDecisionReturnsConflictWithoutMutation() {
		OffsetDateTime original = OffsetDateTime.parse("2026-07-14T01:02:03Z");
		insertMessageReport(10L, 2L, "confirmed", "cancelled", 8L, original);

		assertThatThrownBy(() -> inTransaction(() -> service.dismiss(10L, 9L)))
			.isInstanceOf(AdminReportAlreadyResolvedException.class);

		assertThat(value("SELECT status::text FROM reports WHERE report_id = 10")).isEqualTo("confirmed");
		assertThat(jdbc.queryForObject("SELECT resolved_by FROM reports WHERE report_id = 10", Long.class))
			.isEqualTo(8L);
	}

	@Test
	void missingReportReturnsNotFound() {
		assertThatThrownBy(() -> inTransaction(() -> service.confirm(999L, 9L)))
			.isInstanceOf(AdminReportNotFoundException.class);
	}

	@Test
	void concurrentOppositeDecisionsYieldOneSuccessAndOneConflict() throws Exception {
		insertMessageReport(10L, 2L, "pending", "pending", null, null);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<String> confirm = executor.submit(() -> decideConcurrently(ready, start, () -> service.confirm(10L, 8L)));
			Future<String> dismiss = executor.submit(() -> decideConcurrently(ready, start, () -> service.dismiss(10L, 9L)));
			ready.await();
			start.countDown();

			assertThat(List.of(confirm.get(), dismiss.get())).containsExactlyInAnyOrder("success", "conflict");
		}
		assertThat(value("SELECT status::text FROM reports WHERE report_id = 10"))
			.isIn("confirmed", "dismissed");
		assertThat(value("SELECT ai_review_state::text FROM reports WHERE report_id = 10")).isEqualTo("cancelled");
	}

	@Test
	void concurrentSameDecisionIsIdempotentAndKeepsFirstResolver() throws Exception {
		insertMessageReport(10L, 2L, "pending", "pending", null, null);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<String> first = executor.submit(() -> decideConcurrently(ready, start, () -> service.confirm(10L, 8L)));
			Future<String> second = executor.submit(() -> decideConcurrently(ready, start, () -> service.confirm(10L, 9L)));
			ready.await();
			start.countDown();

			assertThat(List.of(first.get(), second.get())).containsOnly("success");
		}
		assertThat(jdbc.queryForObject("SELECT resolved_by FROM reports WHERE report_id = 10", Long.class))
			.isIn(8L, 9L);
		assertThat(jdbc.queryForObject("SELECT resolved_at FROM reports WHERE report_id = 10", OffsetDateTime.class))
			.isNotNull();
	}

	@Test
	void dismissReleasesAllLinkedAiSanctionsButPreservesAdminAndOtherReportSanctions() {
		insertMessageReport(10L, 2L, "pending", "pending", null, null);
		insertMessageReport(11L, 2L, "pending", "pending", null, null);
		insertSanction(100L, 2L, 10L, "ai_recommendation", null, false);
		insertSanction(101L, 2L, 10L, "ai_recommendation", null, false);
		insertSanction(102L, 2L, 10L, "admin", 8L, false);
		insertSanction(103L, 2L, 11L, "ai_recommendation", null, false);
		insertSanction(104L, 2L, 10L, "ai_recommendation", null, true);

		inTransaction(() -> service.dismiss(10L, 9L));

		assertThat(count("""
			SELECT COUNT(*) FROM user_sanctions
			WHERE report_id = 10 AND decision_source = 'ai_recommendation' AND released_by = 9
			""")).isEqualTo(2);
		assertThat(count("""
			SELECT COUNT(*) FROM user_sanctions
			WHERE sanction_id IN (102, 103) AND released_at IS NULL
			""")).isEqualTo(2);
		assertThat(value("SELECT status::text FROM users WHERE user_id = 2")).isEqualTo("suspended");
	}

	@Test
	void dismissActivatesUserOnlyAfterEveryActiveSanctionIsGone() {
		insertMessageReport(10L, 2L, "pending", "pending", null, null);
		insertSanction(100L, 2L, 10L, "ai_recommendation", null, false);
		insertSanction(101L, 2L, 10L, "ai_recommendation", null, false);

		inTransaction(() -> service.dismiss(10L, 9L));

		assertThat(count("SELECT COUNT(*) FROM user_sanctions WHERE released_at IS NULL")).isZero();
		assertThat(value("SELECT status::text FROM users WHERE user_id = 2")).isEqualTo("active");
	}

	@Test
	void dismissingAiAnswerWithNoReportedUserIsAUserNoOp() {
		insertAiAnswerReport(12L);

		inTransaction(() -> service.dismiss(12L, 9L));

		assertThat(value("SELECT status::text FROM reports WHERE report_id = 12")).isEqualTo("dismissed");
		assertThat(value("SELECT status::text FROM users WHERE user_id = 2")).isEqualTo("suspended");
	}

	private String decideConcurrently(CountDownLatch ready, CountDownLatch start, Runnable decision)
		throws InterruptedException {
		ready.countDown();
		start.await();
		try {
			inTransaction(decision);
			return "success";
		} catch (AdminReportAlreadyResolvedException exception) {
			return "conflict";
		}
	}

	private UUID insertMessageReport(
		long reportId,
		Long reportedUserId,
		String status,
		String aiState,
		Long resolvedBy,
		OffsetDateTime resolvedAt
	) {
		UUID attemptId = "processing".equals(aiState) ? UUID.randomUUID() : null;
		OffsetDateTime nextAttemptAt = switch (aiState) {
			case "pending", "retry" -> OffsetDateTime.now().plusMinutes(1);
			default -> null;
		};
		OffsetDateTime leaseUntil = "processing".equals(aiState) ? OffsetDateTime.now().plusHours(1) : null;
		String lockedBy = "processing".equals(aiState) ? "worker-1" : null;
		jdbc.update("""
			INSERT INTO reports(
				report_id, reporter_id, target_type, message_id, answer_id, reported_user_id, reason, detail,
				context_snapshot, context_hash, ai_review_state, ai_review_attempt_id, ai_attempts,
				ai_next_attempt_at, ai_lease_until, ai_locked_by, status, resolved_by, resolved_at, created_at
			) VALUES (?, 1, 'message', NULL, NULL, ?, 'abuse', 'detail',
				 jsonb_build_object('reported', jsonb_build_object('messageId', ?)), repeat('a', 64),
				 ?::ai_job_status, ?, 1, ?::timestamptz, ?::timestamptz, ?, ?::report_status, ?, ?::timestamptz,
				 CURRENT_TIMESTAMP)
			""",
			reportId, reportedUserId, reportId * 100, aiState, attemptId, nextAttemptAt, leaseUntil,
			lockedBy, status, resolvedBy, resolvedAt
		);
		return attemptId;
	}

	private void insertAiAnswerReport(long reportId) {
		jdbc.update("""
			INSERT INTO reports(
				report_id, reporter_id, target_type, answer_id, reported_user_id, reason, detail,
				context_snapshot, context_hash, ai_review_state, ai_attempts, ai_next_attempt_at,
				status, created_at
			) VALUES (?, 1, 'answer', NULL, NULL, 'etc', 'detail',
				 jsonb_build_object('reported', jsonb_build_object('answerId', ?)), repeat('a', 64),
				 'cancelled', 0, NULL, 'pending', CURRENT_TIMESTAMP)
			""",
			reportId, reportId * 100
		);
	}

	private void insertSanction(
		long sanctionId,
		long userId,
		long reportId,
		String source,
		Long adminId,
		boolean released
	) {
		jdbc.update("""
			INSERT INTO user_sanctions(
				sanction_id, user_id, report_id, decision_source, admin_id, sanction_type, reason,
				starts_at, ends_at, released_at, released_by, created_at
			) VALUES (?, ?, ?, ?::sanction_decision_source, ?,
				 CASE WHEN ? = 'admin' THEN 'permanent'::sanction_type ELSE 'temporary'::sanction_type END,
				 'reason', CURRENT_TIMESTAMP, CASE WHEN ? = 'admin' THEN NULL ELSE CURRENT_TIMESTAMP + INTERVAL '7 days' END,
				 CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
				 CASE WHEN ? THEN 8 ELSE NULL END, CURRENT_TIMESTAMP + (? * INTERVAL '1 second'))
			""",
			sanctionId, userId, reportId, source, adminId, source, source, released, released, sanctionId
		);
	}

	private void inTransaction(Runnable action) {
		transaction.executeWithoutResult(status -> action.run());
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private String value(String sql) {
		return jdbc.queryForObject(sql, String.class);
	}
}
