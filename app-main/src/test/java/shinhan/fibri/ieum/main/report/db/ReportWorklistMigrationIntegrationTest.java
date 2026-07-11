package shinhan.fibri.ieum.main.report.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class ReportWorklistMigrationIntegrationTest {

	private static final String DATABASE = "ieum_main_report_worklist";

	@Test
	void v14ExpandsReportsIntoDataPreservingAiWorklist() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "test-baselines/schema-v12.sql", "migrations/v13_app_ai_v2_expand.sql");

		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedV13Reports(jdbc);

		List<ReportRow> beforeReports = reportRows(jdbc);
		String usersFingerprintBefore = tableFingerprint(jdbc, "users");
		String sanctionsFingerprintBefore = tableFingerprint(jdbc, "user_sanctions");

		SqlScriptRunner.run(DATABASE, "migrations/v14_report_worklist_expand.sql");

		List<ReportRow> afterReports = reportRows(jdbc);
		assertThat(afterReports).usingRecursiveComparison().isEqualTo(beforeReports);
		assertThat(count(jdbc, "reports")).isEqualTo(4);
		assertThat(tableFingerprint(jdbc, "users")).isEqualTo(usersFingerprintBefore);
		assertThat(tableFingerprint(jdbc, "user_sanctions")).isEqualTo(sanctionsFingerprintBefore);

		assertPendingReportBecameDueWork(jdbc);
		assertNonPendingReportsBecameDeadWork(jdbc);
		assertNullSnapshotHashSemantics(jdbc);
		assertV14ColumnsConstraintsAndIndexes(jdbc);
	}

	private static void seedV13Reports(JdbcClient jdbc) {
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status, created_at, updated_at)
			VALUES
			    (1, 'reporter@example.com', 'email', 'hash', 'reporter', true, 'user', 'active', '2026-07-11T00:00:00Z', '2026-07-11T00:00:00Z'),
			    (2, 'reported@example.com', 'email', 'hash', 'reported', true, 'user', 'active', '2026-07-11T00:00:00Z', '2026-07-11T00:00:00Z'),
			    (3, 'admin@example.com', 'email', 'hash', 'admin', true, 'admin', 'active', '2026-07-11T00:00:00Z', '2026-07-11T00:00:00Z')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key, created_at)
			VALUES (10, 'direct', 'd:1:2', '2026-07-11T00:00:00Z')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_members (room_id, user_id, joined_at)
			VALUES (10, 1, '2026-07-11T00:00:00Z'), (10, 2, '2026-07-11T00:00:00Z')
			""").update();
		jdbc.sql("""
			INSERT INTO messages (message_id, room_id, sender_id, content, created_at)
			VALUES
			    (100, 10, 2, 'pending message', '2026-07-11T00:01:00Z'),
			    (101, 10, 2, 'reviewed message', '2026-07-11T00:02:00Z'),
			    (102, 10, 2, 'confirmed message', '2026-07-11T00:03:00Z'),
			    (103, 10, 2, 'dismissed message', '2026-07-11T00:04:00Z')
			""").update();
		jdbc.sql("""
			INSERT INTO reports (
			    report_id, reporter_id, message_id, reported_user_id, reason, detail, context_snapshot,
			    ai_recommendation, ai_reason, ai_confidence, ai_model_version, ai_policy_version,
			    ai_reviewed_at, status, resolved_by, resolved_at, created_at
			)
			VALUES
			    (1000, 1, 100, 2, 'spam', 'pending detail', '{"schemaVersion":1,"case":"pending"}'::jsonb,
			        'temporary_suspend', 'legacy pending reason', 0.9100, 'legacy-model-a', 'policy-a',
			        '2026-07-11T01:01:00Z', 'pending', NULL, NULL, '2026-07-11T01:00:00Z'),
			    (1001, 1, 101, 2, 'abuse', 'reviewed detail', NULL,
			        'hold', 'legacy reviewed reason', 0.6200, 'legacy-model-b', 'policy-b',
			        '2026-07-11T02:01:00Z', 'ai_reviewed', NULL, NULL, '2026-07-11T02:00:00Z'),
			    (1002, 1, 102, 2, 'harassment', 'confirmed detail', '{"schemaVersion":1,"case":"confirmed"}'::jsonb,
			        'temporary_suspend', 'legacy confirmed reason', 0.9900, 'legacy-model-c', 'policy-c',
			        '2026-07-11T03:01:00Z', 'confirmed', 3, '2026-07-11T03:02:00Z', '2026-07-11T03:00:00Z'),
			    (1003, 1, 103, 2, 'etc', 'dismissed detail', NULL,
			        'dismiss', 'legacy dismissed reason', 0.1200, 'legacy-model-d', 'policy-d',
			        '2026-07-11T04:01:00Z', 'dismissed', 3, '2026-07-11T04:02:00Z', '2026-07-11T04:00:00Z')
			""").update();
		jdbc.sql("""
			INSERT INTO user_sanctions (sanction_id, user_id, report_id, decision_source, admin_id, sanction_type, reason, starts_at, ends_at, created_at)
			VALUES (2000, 2, NULL, 'admin', 3, 'temporary', 'pre-v14 sanction', '2026-07-10T00:00:00Z', '2026-07-12T00:00:00Z', '2026-07-10T00:00:00Z')
			""").update();
	}

	private static void assertPendingReportBecameDueWork(JdbcClient jdbc) {
		Map<String, Object> row = jdbc.sql("""
			SELECT ai_review_state::text, ai_attempts, ai_next_attempt_at, ai_lease_until, ai_locked_by,
			       ai_last_error_code, ai_review_result, created_at
			FROM reports
			WHERE report_id = 1000
			""").query().singleRow();

		assertThat(row.get("ai_review_state")).isEqualTo("pending");
		assertThat(((Number) row.get("ai_attempts")).intValue()).isZero();
		assertThat(row.get("ai_next_attempt_at")).isEqualTo(row.get("created_at"));
		assertThat(row.get("ai_lease_until")).isNull();
		assertThat(row.get("ai_locked_by")).isNull();
		assertThat(row.get("ai_last_error_code")).isNull();
		assertThat(row.get("ai_review_result")).isNull();
	}

	private static void assertNonPendingReportsBecameDeadWork(JdbcClient jdbc) {
		List<Map<String, Object>> rows = jdbc.sql("""
			SELECT report_id, ai_review_state::text, ai_attempts, ai_next_attempt_at, ai_last_error_code, ai_review_result
			FROM reports
			WHERE status <> 'pending'
			ORDER BY report_id
			""").query().listOfRows();

		assertThat(rows).hasSize(3);
		for (Map<String, Object> row : rows) {
			assertThat(row.get("ai_review_state")).isEqualTo("dead");
			assertThat(((Number) row.get("ai_attempts")).intValue()).isZero();
			assertThat(row.get("ai_next_attempt_at")).isNull();
			assertThat(row.get("ai_last_error_code")).isEqualTo("LEGACY_NON_PENDING_REPORT");
			assertThat(row.get("ai_review_result")).isNull();
		}
	}

	private static void assertNullSnapshotHashSemantics(JdbcClient jdbc) {
		String literalNullHash = jdbc.sql("SELECT encode(digest(convert_to('null', 'UTF8'), 'sha256'), 'hex')")
			.query(String.class)
			.single();

		List<Map<String, Object>> rows = jdbc.sql("""
			SELECT report_id, context_snapshot IS NULL AS snapshot_is_null, context_hash
			FROM reports
			WHERE report_id IN (1001, 1003)
			ORDER BY report_id
			""").query().listOfRows();

		assertThat(rows).hasSize(2);
		for (Map<String, Object> row : rows) {
			assertThat(row.get("snapshot_is_null")).isEqualTo(true);
			assertThat(row.get("context_hash")).isEqualTo(literalNullHash);
		}
	}

	private static void assertV14ColumnsConstraintsAndIndexes(JdbcClient jdbc) {
		assertThat(columns(jdbc, "reports")).contains(
			"context_hash",
			"ai_review_state",
			"ai_review_attempt_id",
			"ai_attempts",
			"ai_next_attempt_at",
			"ai_lease_until",
			"ai_locked_by",
			"ai_last_error_code",
			"ai_last_error_message",
			"ai_decision",
			"ai_policy_set_hash",
			"ai_review_result",
			"ai_recommendation",
			"ai_policy_version");
		assertThat(notNullColumns(jdbc, "reports")).contains("context_hash", "ai_review_state", "ai_attempts");
		assertThat(validCheckConstraints(jdbc, "reports")).contains(
			"ck_reports_ai_attempts",
			"ck_reports_ai_processing_lease",
			"ck_reports_ai_due_work",
			"ck_reports_ai_completed",
			"ck_reports_ai_dead");
		assertThat(indexDefinitions(jdbc, "reports")).contains(
			"CREATE INDEX idx_reports_ai_due ON public.reports USING btree (ai_next_attempt_at, created_at, report_id) WHERE (ai_review_state = ANY (ARRAY['pending'::ai_job_status, 'retry'::ai_job_status]))",
			"CREATE INDEX idx_reports_ai_expired_lease ON public.reports USING btree (ai_lease_until, report_id) WHERE (ai_review_state = 'processing'::ai_job_status)",
			"CREATE INDEX idx_reports_status ON public.reports USING btree (status, created_at DESC)",
			"CREATE INDEX idx_reports_reported_user ON public.reports USING btree (reported_user_id)");
	}

	private static List<ReportRow> reportRows(JdbcClient jdbc) {
		return jdbc.sql("""
			SELECT report_id, reporter_id, message_id, reported_user_id, reason::text, detail,
			       context_snapshot::text, ai_recommendation::text, ai_reason, ai_confidence,
			       ai_model_version, ai_policy_version, ai_reviewed_at, status::text,
			       resolved_by, resolved_at, created_at
			FROM reports
			ORDER BY report_id
			""")
			.query((rs, rowNum) -> new ReportRow(
				rs.getLong("report_id"),
				rs.getLong("reporter_id"),
				rs.getLong("message_id"),
				rs.getLong("reported_user_id"),
				rs.getString("reason"),
				rs.getString("detail"),
				rs.getString("context_snapshot"),
				rs.getString("ai_recommendation"),
				rs.getString("ai_reason"),
				rs.getBigDecimal("ai_confidence"),
				rs.getString("ai_model_version"),
				rs.getString("ai_policy_version"),
				rs.getObject("ai_reviewed_at", OffsetDateTime.class),
				rs.getString("status"),
				(Long) rs.getObject("resolved_by"),
				rs.getObject("resolved_at", OffsetDateTime.class),
				rs.getObject("created_at", OffsetDateTime.class)))
			.list();
	}

	private static String tableFingerprint(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT encode(digest(COALESCE(string_agg(to_jsonb(t)::text, ',' ORDER BY to_jsonb(t)::text), ''), 'sha256'), 'hex')
			FROM %s t
			""".formatted(tableName)).query(String.class).single();
	}

	private static long count(JdbcClient jdbc, String tableName) {
		return jdbc.sql("SELECT count(*) FROM " + tableName).query(Long.class).single();
	}

	private static List<String> columns(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = :tableName
			ORDER BY ordinal_position
			""").param("tableName", tableName).query(String.class).list();
	}

	private static List<String> notNullColumns(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = :tableName AND is_nullable = 'NO'
			ORDER BY ordinal_position
			""").param("tableName", tableName).query(String.class).list();
	}

	private static List<String> validCheckConstraints(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT conname
			FROM pg_constraint
			WHERE conrelid = (:tableName)::regclass AND contype = 'c' AND convalidated
			ORDER BY conname
			""").param("tableName", tableName).query(String.class).list();
	}

	private static List<String> indexDefinitions(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT indexdef
			FROM pg_indexes
			WHERE schemaname = 'public' AND tablename = :tableName
			ORDER BY indexname
			""").param("tableName", tableName).query(String.class).list();
	}

	private record ReportRow(
		long reportId,
		long reporterId,
		long messageId,
		long reportedUserId,
		String reason,
		String detail,
		String contextSnapshot,
		String aiRecommendation,
		String aiReason,
		BigDecimal aiConfidence,
		String aiModelVersion,
		String aiPolicyVersion,
		OffsetDateTime aiReviewedAt,
		String status,
		Long resolvedBy,
		OffsetDateTime resolvedAt,
		OffsetDateTime createdAt) {
	}
}
