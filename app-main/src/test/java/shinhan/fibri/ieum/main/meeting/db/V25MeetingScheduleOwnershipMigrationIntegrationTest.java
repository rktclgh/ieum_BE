package shinhan.fibri.ieum.main.meeting.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class V25MeetingScheduleOwnershipMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v25_meeting_schedule_ownership";

	private JdbcClient jdbc;

	@BeforeEach
	void recreateDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "test-baselines/schema-v12.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedLegacyMeeting();
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void v25AllowsUnscheduledMeetingsAndBackfillsScheduleCreator() {
		SqlScriptRunner.run(DATABASE, "migrations/v25_meeting_schedule_ownership.sql");

		assertThat(columnNullable("meetings", "meeting_at")).isTrue();
		assertThat(jdbc.sql("SELECT created_by FROM meeting_schedules WHERE schedule_id = 31")
			.query(Long.class)
			.single()).isEqualTo(42L);
		assertThat(constraintValidated("meeting_schedules", "fk_meeting_schedules_created_by")).isTrue();

		jdbc.sql("""
			INSERT INTO pins (pin_id, author_id, pin_type, location, address)
			VALUES (12, 42, 'meeting', ST_SetSRID(ST_MakePoint(127.1, 37.6), 4326)::geography, '서울')
			""").update();
		jdbc.sql("""
			INSERT INTO meetings (meeting_id, pin_id, host_id, title, type, meeting_at, max_members)
			VALUES (4, 12, 42, '일정 미정 모임', 'one_time', NULL, 4)
			""").update();

		assertThat(jdbc.sql("SELECT meeting_at IS NULL FROM meetings WHERE meeting_id = 4")
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void v25IsIdempotentAndPreservesScheduleWhenCreatorIsHardDeleted() {
		SqlScriptRunner.run(
			DATABASE,
			"migrations/v25_meeting_schedule_ownership.sql",
			"migrations/v25_meeting_schedule_ownership.sql"
		);
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status)
			VALUES (77, 'creator@example.com', 'email', 'hash', 'creator', true, 'user', 'active')
			""").update();
		jdbc.sql("UPDATE meeting_schedules SET created_by = 77 WHERE schedule_id = 31").update();

		jdbc.sql("DELETE FROM users WHERE user_id = 77").update();

		assertThat(jdbc.sql("SELECT created_by IS NULL FROM meeting_schedules WHERE schedule_id = 31")
			.query(Boolean.class)
			.single()).isTrue();
		assertThat(foreignKeyDeleteAction("meeting_schedules", "fk_meeting_schedules_created_by"))
			.isEqualTo("n");
	}

	@Test
	void v25IsForwardOnly() throws Exception {
		String sql;
		try (InputStream input = Objects.requireNonNull(
			getClass().getClassLoader().getResourceAsStream(
				"canonical-db/migrations/v25_meeting_schedule_ownership.sql"
			)
		)) {
			sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
		}

		assertThat(sql)
			.doesNotContain("DROP DATABASE")
			.doesNotContain("DROP TABLE")
			.doesNotContain("TRUNCATE");
	}

	private void seedLegacyMeeting() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status)
			VALUES (42, 'host@example.com', 'email', 'hash', 'host', true, 'user', 'active')
			""").update();
		jdbc.sql("""
			INSERT INTO pins (pin_id, author_id, pin_type, location, address)
			VALUES (11, 42, 'meeting', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울')
			""").update();
		jdbc.sql("""
			INSERT INTO meetings (meeting_id, pin_id, host_id, title, type, meeting_at, max_members)
			VALUES (3, 11, 42, '기존 모임', 'one_time', '2026-07-20T10:00:00+09:00', 4)
			""").update();
		jdbc.sql("""
			INSERT INTO meeting_schedules (
				schedule_id, meeting_id, starts_at, visible_until, status, sequence_no
			)
			VALUES (
				31, 3, '2026-07-20T10:00:00+09:00', '2026-07-20T23:59:59+09:00', 'scheduled', 1
			)
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

	private boolean constraintValidated(String table, String constraint) {
		return jdbc.sql("""
			SELECT convalidated
			FROM pg_constraint
			WHERE conrelid = (:table)::regclass AND conname = :constraint
			""")
			.param("table", table)
			.param("constraint", constraint)
			.query(Boolean.class)
			.single();
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
