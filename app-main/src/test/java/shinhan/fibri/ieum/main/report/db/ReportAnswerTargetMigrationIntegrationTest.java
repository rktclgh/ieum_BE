package shinhan.fibri.ieum.main.report.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class ReportAnswerTargetMigrationIntegrationTest {

	private static final String DATABASE = "ieum_main_report_answer_target";
	private static final String SNAPSHOT = "{\"schemaVersion\":1,\"reported\":{\"messageId\":100}}";
	private static final String HASH = "a".repeat(64);

	private JdbcClient jdbc;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(
			DATABASE,
			"test-baselines/schema-v12.sql",
			"migrations/v13_app_ai_v2_expand.sql",
			"migrations/v14_report_worklist_expand.sql",
			"migrations/v15_question_ai_ticket_notification.sql",
			"migrations/v16_question_ai_finalization_lock.sql",
			"migrations/v17_question_ai_checkpoints.sql",
			"migrations/v18_knowledge_source_content_hash.sql"
		);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedLegacyMessageReport();
	}

	@Test
	void v20ThenV21BackfillsLegacyMessageTargetsWithoutRewritingEvidenceOrAiWork() {
		Map<String, Object> before = legacyReportRow(1000L);

		runReportTargetMigrations();

		Map<String, Object> after = reportRow(1000L);
		assertThat(after.get("target_type")).isEqualTo("message");
		assertThat(after.get("answer_id")).isNull();
		assertThat(after.get("message_id")).isEqualTo(100L);
		assertThat(after.get("context_snapshot")).isEqualTo(before.get("context_snapshot"));
		assertThat(after.get("context_hash")).isEqualTo(before.get("context_hash"));
		assertThat(after.get("ai_review_state")).isEqualTo(before.get("ai_review_state"));
		assertThat(after.get("ai_next_attempt_at")).isEqualTo(before.get("ai_next_attempt_at"));
	}

	@Test
	void mergedV20RemainsImmutable() throws IOException {
		String migration = readSqlResource("migrations/v20_answer_report_target.sql");

		assertThat(migration)
			.contains("ADD COLUMN answer_id BIGINT REFERENCES answers(answer_id) ON DELETE SET NULL")
			.contains("CREATE INDEX idx_reports_answer")
			.contains("CONSTRAINT = 'ck_reports_target_required'")
			.doesNotContain("CREATE INDEX CONCURRENTLY")
			.doesNotContain("fk_reports_answer");
	}

	@Test
	void v21NormalizesMergedV20MetadataAndIsRerunnable() {
		SqlScriptRunner.run(DATABASE, "migrations/v20_answer_report_target.sql");

		assertThat(foreignKeyNames()).contains("reports_answer_id_fkey").doesNotContain("fk_reports_answer");
		assertThat(reportTargetFunctionDefinition()).contains("ck_reports_target_required");

		SqlScriptRunner.run(DATABASE, "migrations/v21_report_target_review_followup.sql");

		assertThat(foreignKeyNames()).contains("fk_reports_answer").doesNotContain("reports_answer_id_fkey");
		assertThat(reportTargetFunctionDefinition())
			.contains("ck_reports_target_xor")
			.doesNotContain("ck_reports_target_required");

		SqlScriptRunner.run(DATABASE, "migrations/v21_report_target_review_followup.sql");
		assertThat(foreignKeyNames()).contains("fk_reports_answer").doesNotContain("reports_answer_id_fkey");
	}

	@Test
	void v20ThenV21AllowsManualAiAnswerReportAndKeepsTypeAfterAnswerDeletion() {
		runReportTargetMigrations();
		long answerId = seedQuestionAndAiAnswer();
		long reportId = insertAnswerReport(answerId, null, "cancelled");

		jdbc.sql("DELETE FROM answers WHERE answer_id = :answerId")
			.param("answerId", answerId)
			.update();

		Map<String, Object> row = reportRow(reportId);
		assertThat(row.get("target_type")).isEqualTo("answer");
		assertThat(row.get("answer_id")).isNull();
		assertThat(row.get("reported_user_id")).isNull();
		assertThat(row.get("ai_review_state")).isEqualTo("cancelled");
	}

	@Test
	void v20ThenV21RejectsCrossTargetIdsMissingMessageUserAndPendingAnswerWork() {
		runReportTargetMigrations();
		long answerId = seedQuestionAndAiAnswer();

		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, target_type, message_id, answer_id, reason, context_hash,
			    ai_review_state, ai_next_attempt_at
			)
			VALUES (1, 'answer', 100, :answerId, 'abuse', :hash, 'cancelled', NULL)
			""")
			.param("answerId", answerId)
			.param("hash", HASH)
			.update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_reports_target_xor");

		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, target_type, message_id, reported_user_id, reason, context_hash
			)
			VALUES (1, 'message', 100, NULL, 'abuse', :hash)
			""")
			.param("hash", HASH)
			.update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_reports_message_reported_user");

		assertThatThrownBy(() -> insertAnswerReport(answerId, null, "pending"))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_reports_answer_manual_only");
	}

	@Test
	void v20ThenV21RejectsMissingSelectedTargetAndInvalidAnswerAttributionAtCreation() {
		runReportTargetMigrations();

		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, target_type, message_id, answer_id, reported_user_id, reason,
			    context_hash, ai_review_state, ai_next_attempt_at
			)
			VALUES (1, 'message', NULL, NULL, 2, 'abuse', :hash, 'cancelled', NULL)
			""")
			.param("hash", HASH)
			.update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("report selected target is required at creation");

		long humanAnswerId = seedQuestionAndHumanAnswer();
		assertThatThrownBy(() -> insertAnswerReport(humanAnswerId, null, "cancelled"))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("reported user must match the answer author semantics");

		long aiAnswerId = seedQuestionAndAiAnswer();
		assertThatThrownBy(() -> insertAnswerReport(aiAnswerId, 2L, "cancelled"))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("reported user must match the answer author semantics");
	}

	@Test
	void v20ThenV21RejectsManualTargetNullingWhileTargetStillExists() {
		runReportTargetMigrations();
		long answerId = seedQuestionAndAiAnswer();
		long reportId = insertAnswerReport(answerId, null, "cancelled");

		assertThatThrownBy(() -> jdbc.sql("UPDATE reports SET answer_id = NULL WHERE report_id = :reportId")
			.param("reportId", reportId)
			.update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("report answer target may only be cleared by target deletion");
	}

	private void runReportTargetMigrations() {
		SqlScriptRunner.run(
			DATABASE,
			"migrations/v20_answer_report_target.sql",
			"migrations/v21_report_target_review_followup.sql"
		);
	}

	private java.util.List<String> foreignKeyNames() {
		return jdbc.sql("""
			SELECT conname
			FROM pg_constraint
			WHERE conrelid = 'reports'::regclass AND contype = 'f'
			ORDER BY conname
			""").query(String.class).list();
	}

	private String reportTargetFunctionDefinition() {
		return jdbc.sql("""
			SELECT pg_get_functiondef('public.enforce_report_target_integrity()'::regprocedure)
			""").query(String.class).single();
	}

	private String readSqlResource(String resourceName) throws IOException {
		try (InputStream input = Objects.requireNonNull(
			getClass().getClassLoader().getResourceAsStream("canonical-db/" + resourceName),
			"Missing SQL resource: " + resourceName
		)) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private void seedLegacyMessageReport() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status)
			VALUES
			    (1, 'reporter@example.com', 'email', 'hash', 'reporter', true, 'user', 'active'),
			    (2, 'reported@example.com', 'email', 'hash', 'reported', true, 'user', 'active')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key)
			VALUES (10, 'direct', 'd:1:2')
			""").update();
		jdbc.sql("""
			INSERT INTO messages (message_id, room_id, sender_id, content)
			VALUES (100, 10, 2, 'reported message')
			""").update();
		jdbc.sql("""
			INSERT INTO reports (
			    report_id, reporter_id, message_id, reported_user_id, reason, context_snapshot,
			    context_hash, ai_review_state, ai_attempts, ai_next_attempt_at, status
			)
			VALUES (
			    1000, 1, 100, 2, 'abuse', CAST(:snapshot AS jsonb),
			    :hash, 'pending', 0, '2026-07-14T00:00:00Z', 'pending'
			)
			""")
			.param("snapshot", SNAPSHOT)
			.param("hash", HASH)
			.update();
	}

	private long seedQuestionAndAiAnswer() {
		long questionId = seedQuestion();
		return jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (:questionId, NULL, TRUE, 'AI answer')
			RETURNING answer_id
			""").param("questionId", questionId).query(Long.class).single();
	}

	private long seedQuestionAndHumanAnswer() {
		long questionId = seedQuestion();
		return jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (:questionId, 2, FALSE, 'human answer')
			RETURNING answer_id
			""").param("questionId", questionId).query(Long.class).single();
	}

	private long seedQuestion() {
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
		return questionId;
	}

	private long insertAnswerReport(long answerId, Long reportedUserId, String aiReviewState) {
		return jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, target_type, answer_id, reported_user_id, reason, context_snapshot,
			    context_hash, ai_review_state, ai_next_attempt_at, status
			)
			VALUES (
			    1, 'answer', :answerId, :reportedUserId, 'abuse', '{"targetType":"answer"}'::jsonb,
			    :hash, CAST(:aiReviewState AS ai_job_status),
			    CASE WHEN :aiReviewState = 'pending' THEN CURRENT_TIMESTAMP ELSE NULL END,
			    'pending'
			)
			RETURNING report_id
			""")
			.param("answerId", answerId)
			.param("reportedUserId", reportedUserId)
			.param("hash", HASH)
			.param("aiReviewState", aiReviewState)
			.query(Long.class)
			.single();
	}

	private Map<String, Object> reportRow(long reportId) {
		return jdbc.sql("""
			SELECT report_id,
			       target_type::text AS target_type, message_id, answer_id,
			       reported_user_id, context_snapshot::text AS context_snapshot, context_hash,
			       ai_review_state::text AS ai_review_state, ai_next_attempt_at
			FROM reports
			WHERE report_id = :reportId
			""")
			.param("reportId", reportId)
			.query()
			.singleRow();
	}

	private Map<String, Object> legacyReportRow(long reportId) {
		return jdbc.sql("""
			SELECT report_id, message_id, reported_user_id, context_snapshot::text AS context_snapshot,
			       context_hash, ai_review_state::text AS ai_review_state, ai_next_attempt_at
			FROM reports
			WHERE report_id = :reportId
			""")
			.param("reportId", reportId)
			.query()
			.singleRow();
	}
}
