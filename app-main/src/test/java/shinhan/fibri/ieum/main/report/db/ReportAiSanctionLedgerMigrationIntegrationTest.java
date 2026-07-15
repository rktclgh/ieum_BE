package shinhan.fibri.ieum.main.report.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class ReportAiSanctionLedgerMigrationIntegrationTest {

	private static final String DATABASE = "ieum_main_report_ai_sanction_ledger";
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
			"migrations/v18_knowledge_source_content_hash.sql",
			"migrations/v19_knowledge_import_lifecycle.sql",
			"migrations/v20_answer_report_target.sql",
			"migrations/v21_report_target_review_followup.sql",
			"migrations/v22_accepted_answer_eligibility_lock.sql"
		);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedLegacyRows();
	}

	@Test
	void v23AddsADataPreservingCumulativeAiSanctionLedger() {
		Map<String, Object> before = legacySanctionRow(100L);

		SqlScriptRunner.run(DATABASE, "migrations/v23_report_ai_sanction_ledger.sql");

		Map<String, Object> after = legacySanctionRow(100L);
		Map<String, Object> ledger = sanctionLedgerRow(100L);
		assertThat(after).isEqualTo(before);
		assertThat(ledger.get("duration_minutes")).isEqualTo(120);
		assertThat(ledger.get("review_status")).isEqualTo("not_required");
		assertThat(ledger.get("revoked_at")).isNull();
		assertThat(columns("user_sanctions")).contains(
			"duration_minutes", "revoked_at", "revoked_by", "review_status"
		);
		assertThat(indexNames("user_sanctions")).contains("uq_user_sanctions_ai_report");
		assertThat(constraintNames("reports")).contains("ck_reports_ai_completed_projection");
	}

	@Test
	void v23RejectsTwoAutomaticSanctionsForTheSameReport() {
		SqlScriptRunner.run(DATABASE, "migrations/v23_report_ai_sanction_ledger.sql");

		insertAiSanction(200L, 1000L);
		assertThatThrownBy(() -> insertAiSanction(201L, 1000L))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void v23AddsAndValidatesTheRevocationActorForeignKey() {
		SqlScriptRunner.run(DATABASE, "migrations/v23_report_ai_sanction_ledger.sql");

		assertThat(validatedConstraint("user_sanctions", "fk_user_sanctions_revoked_by")).isTrue();
	}

	@Test
	void v23AllowsReportDeletionOnlyAfterTheAiSanctionIsRevoked() {
		SqlScriptRunner.run(DATABASE, "migrations/v23_report_ai_sanction_ledger.sql");
		insertAiSanction(200L, 1000L);

		assertThatThrownBy(() -> deleteReport(1000L))
			.isInstanceOf(DataIntegrityViolationException.class);

		jdbc.sql("""
			UPDATE user_sanctions
			SET revoked_at = CURRENT_TIMESTAMP,
			    revoked_by = 3,
			    review_status = 'dismissed'
			WHERE sanction_id = 200
			""").update();

		deleteReport(1000L);

		assertThat(sanctionLedgerRow(200L).get("report_id")).isNull();
	}

	@Test
	void v23IsForwardOnlyAndContainsNoDestructiveTableOperation() throws Exception {
		String sql;
		try (InputStream input = Objects.requireNonNull(
			getClass().getClassLoader().getResourceAsStream("canonical-db/migrations/v23_report_ai_sanction_ledger.sql")
		)) {
			sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
		}

		assertThat(sql)
			.doesNotContain("DROP DATABASE")
			.doesNotContain("DROP TABLE")
			.doesNotContain("TRUNCATE");
	}

	private void seedLegacyRows() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status)
			VALUES
			    (1, 'reporter@example.com', 'email', 'hash', 'reporter', true, 'user', 'active'),
			    (2, 'reported@example.com', 'email', 'hash', 'reported', true, 'user', 'suspended'),
			    (3, 'admin@example.com', 'email', 'hash', 'admin', true, 'admin', 'active')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key)
			VALUES (10, 'direct', 'd:1:2')
			""").update();
		jdbc.sql("""
			INSERT INTO messages (message_id, room_id, sender_id, content)
			VALUES (10, 10, 2, 'reported')
			""").update();
		jdbc.sql("""
			INSERT INTO reports (
			    report_id, reporter_id, target_type, message_id, reported_user_id, reason,
			    context_hash, ai_review_state, ai_next_attempt_at
			)
			VALUES (1000, 1, 'message', 10, 2, 'abuse', :hash, 'pending', CURRENT_TIMESTAMP)
			""").param("hash", "a".repeat(64)).update();
		jdbc.sql("""
			INSERT INTO user_sanctions (
			    sanction_id, user_id, decision_source, admin_id, sanction_type, reason,
			    starts_at, ends_at, created_at
			)
			VALUES (
			    100, 2, 'admin', 3, 'temporary', 'legacy admin sanction',
			    '2026-07-14T00:00:00Z', '2026-07-14T02:00:00Z', '2026-07-14T00:00:00Z'
			)
			""").update();
	}

	private void insertAiSanction(long sanctionId, long reportId) {
		jdbc.sql("""
			INSERT INTO user_sanctions (
			    sanction_id, user_id, report_id, decision_source, admin_id, sanction_type, reason,
			    starts_at, ends_at, duration_minutes, review_status, created_at
			)
			VALUES (
			    :sanctionId, 2, :reportId, 'ai_recommendation', NULL, 'temporary', 'automatic sanction',
			    '2026-07-15T00:00:00Z', '2026-07-18T00:00:00Z', 4320, 'pending_review', CURRENT_TIMESTAMP
			)
			""")
			.param("sanctionId", sanctionId)
			.param("reportId", reportId)
			.update();
	}

	private void deleteReport(long reportId) {
		jdbc.sql("DELETE FROM reports WHERE report_id = :reportId")
			.param("reportId", reportId)
			.update();
	}

	private Map<String, Object> legacySanctionRow(long sanctionId) {
		return jdbc.sql("""
			SELECT sanction_id, user_id, report_id, decision_source::text, admin_id,
			       sanction_type::text, reason, starts_at, ends_at, created_at,
			       released_at, released_by
			FROM user_sanctions
			WHERE sanction_id = :sanctionId
			""").param("sanctionId", sanctionId).query().singleRow();
	}

	private Map<String, Object> sanctionLedgerRow(long sanctionId) {
		return jdbc.sql("""
			SELECT report_id, duration_minutes, review_status::text, revoked_at, revoked_by
			FROM user_sanctions
			WHERE sanction_id = :sanctionId
			""").param("sanctionId", sanctionId).query().singleRow();
	}

	private List<String> columns(String table) {
		return jdbc.sql("""
			SELECT column_name FROM information_schema.columns
			WHERE table_schema='public' AND table_name=:table ORDER BY ordinal_position
			""").param("table", table).query(String.class).list();
	}

	private List<String> indexNames(String table) {
		return jdbc.sql("""
			SELECT indexname FROM pg_indexes
			WHERE schemaname='public' AND tablename=:table ORDER BY indexname
			""").param("table", table).query(String.class).list();
	}

	private List<String> constraintNames(String table) {
		return jdbc.sql("""
			SELECT conname FROM pg_constraint
			WHERE conrelid=(:table)::regclass ORDER BY conname
			""").param("table", table).query(String.class).list();
	}

	private boolean validatedConstraint(String table, String constraint) {
		return jdbc.sql("""
			SELECT convalidated
			FROM pg_constraint
			WHERE conrelid = (:table)::regclass AND conname = :constraint
			""")
			.param("table", table)
			.param("constraint", constraint)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}
}
