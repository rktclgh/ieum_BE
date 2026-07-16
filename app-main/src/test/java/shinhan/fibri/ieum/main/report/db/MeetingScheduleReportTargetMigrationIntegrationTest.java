package shinhan.fibri.ieum.main.report.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class MeetingScheduleReportTargetMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v31_schedule_report_target";

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
			"migrations/v20_answer_report_target.sql",
			"migrations/v21_report_target_review_followup.sql",
			"migrations/v25_meeting_schedule_ownership.sql"
		);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedMeetingSchedule();
	}

	@AfterAll
	static void cleanUp() {
		JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"))
			.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)")
			.update();
	}

	@Test
	void v29ThroughV31AddScheduleDisplayFieldsAndManualReportTargetThatSurvivesTargetDeletion() {
		runScheduleMigrations();

		assertThat(columnNullable("meeting_schedules", "title")).isTrue();
		assertThat(columnNullable("meeting_schedules", "location_name")).isTrue();
		assertThat(enumLabels()).containsExactly("message", "answer", "schedule");
		assertThat(foreignKeyDeleteAction("reports", "fk_reports_schedule")).isEqualTo("n");

		long reportId = jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, target_type, schedule_id, reported_user_id, reason, context_snapshot,
			    context_hash, ai_review_state, ai_next_attempt_at, status
			)
			VALUES (
			    42, 'schedule', 31, 77, 'spam',
			    '{"schemaVersion":1,"targetType":"schedule","reported":{"scheduleId":31}}'::jsonb,
			    repeat('a', 64), 'cancelled', NULL, 'pending'
			)
			RETURNING report_id
			""").query(Long.class).single();

		assertThat(jdbc.sql("SELECT ai_review_state::text FROM reports WHERE report_id = :reportId")
			.param("reportId", reportId).query(String.class).single()).isEqualTo("cancelled");
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, target_type, schedule_id, reported_user_id, reason, context_snapshot,
			    context_hash, ai_review_state, ai_next_attempt_at, status
			)
			VALUES (
			    42, 'schedule', 31, 42, 'spam',
			    '{"schemaVersion":1,"targetType":"schedule","reported":{"scheduleId":31}}'::jsonb,
			    repeat('b', 64), 'cancelled', NULL, 'pending'
			)
			""").update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE reports SET schedule_id = 32 WHERE report_id = :reportId")
			.param("reportId", reportId).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE reports SET schedule_id = NULL WHERE report_id = :reportId")
			.param("reportId", reportId).update()).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.sql("DELETE FROM meeting_schedules WHERE schedule_id = 31").update();

		assertThat(jdbc.sql("SELECT target_type::text FROM reports WHERE report_id = :reportId")
			.param("reportId", reportId).query(String.class).single()).isEqualTo("schedule");
		assertThat(jdbc.sql("SELECT schedule_id FROM reports WHERE report_id = :reportId")
			.param("reportId", reportId).query(Long.class).optional()).isEmpty();
	}

	private void runScheduleMigrations() {
		SqlScriptRunner.run(
			DATABASE,
			"migrations/v29_meeting_schedule_details.sql",
			"migrations/v30_report_schedule_target_enum.sql",
			"migrations/v31_report_schedule_target.sql"
		);
	}

	private void seedMeetingSchedule() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status)
			VALUES
			    (42, 'reporter@example.com', 'email', 'hash', 'reporter', TRUE, 'user', 'active'),
			    (77, 'owner@example.com', 'email', 'hash', 'owner', TRUE, 'user', 'active')
			""").update();
		jdbc.sql("""
			INSERT INTO pins (pin_id, author_id, pin_type, location, address)
			VALUES (11, 42, 'meeting', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울')
			""").update();
		jdbc.sql("""
			INSERT INTO meetings (meeting_id, pin_id, host_id, title, type, meeting_at, max_members)
			VALUES (3, 11, 42, '기존 모임', 'one_time', '2099-07-20T19:00:00+09:00', 4)
			""").update();
		jdbc.sql("""
			INSERT INTO meeting_schedules (
			    schedule_id, meeting_id, created_by, starts_at, visible_until, status, sequence_no
			)
			VALUES
			    (31, 3, 77, '2099-07-20T19:00:00+09:00', '2099-07-20T23:59:59+09:00', 'scheduled', 1),
			    (32, 3, 77, '2099-07-21T19:00:00+09:00', '2099-07-21T23:59:59+09:00', 'scheduled', 2)
			""").update();
	}

	private boolean columnNullable(String table, String column) {
		return jdbc.sql("""
			SELECT is_nullable = 'YES'
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = :table AND column_name = :column
			""")
			.param("table", table)
			.param("column", column)
			.query(Boolean.class)
			.single();
	}

	private java.util.List<String> enumLabels() {
		return jdbc.sql("""
			SELECT enumlabel
			FROM pg_enum
			WHERE enumtypid = 'report_target_type'::regtype
			ORDER BY enumsortorder
			""").query(String.class).list();
	}

	private String foreignKeyDeleteAction(String table, String constraint) {
		return jdbc.sql("""
			SELECT confdeltype::text
			FROM pg_constraint
			WHERE conrelid = (:table)::regclass AND conname = :constraint
			""")
			.param("table", table)
			.param("constraint", constraint)
			.query(String.class)
			.single();
	}
}
