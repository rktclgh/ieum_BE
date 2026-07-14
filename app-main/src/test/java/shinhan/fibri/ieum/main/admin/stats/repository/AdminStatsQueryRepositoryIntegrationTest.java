package shinhan.fibri.ieum.main.admin.stats.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.AnswerStatsRow;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.ReportStatsRow;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(AdminStatsQueryRepository.class)
class AdminStatsQueryRepositoryIntegrationTest {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres")
	);

	private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-07-01T00:00:00+09:00");
	private static final OffsetDateTime TO = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&prepareThreshold=0");
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		registry.add(
			"spring.jpa.properties.hibernate.dialect",
			() -> "shinhan.fibri.ieum.common.config.SnakeCasePostgreSQLDialect"
		);
	}

	@Autowired
	private AdminStatsQueryRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchemaAndRows() {
		createSchema();
		truncateTables();
		insertRows();
	}

	@Test
	void userStatsCountsSignupsDistinctActiveUsersAndDistinctSuspendedUsersWithinRange() {
		assertThat(repository.countSignups(FROM, TO)).isEqualTo(2);
		assertThat(repository.countActiveUsers(FROM, TO)).isEqualTo(2);
		assertThat(repository.countSuspendedUsers(FROM, TO)).isEqualTo(1);
	}

	@Test
	void contentStatsCountsCreatedContentAndAcceptedAnswersWithinRange() {
		AnswerStatsRow answerStats = repository.getAnswerStats(FROM, TO);

		assertThat(repository.countPins(FROM, TO)).isEqualTo(1);
		assertThat(repository.countQuestions(FROM, TO)).isEqualTo(1);
		assertThat(repository.countMeetings(FROM, TO)).isEqualTo(1);
		assertThat(repository.countMessages(FROM, TO)).isEqualTo(1);
		assertThat(answerStats.total()).isEqualTo(2);
		assertThat(answerStats.accepted()).isEqualTo(1);
	}

	@Test
	void reportStatsUseSeparateEventTimestampsAndSanctionCountUsesRowsNotDistinctUsers() {
		ReportStatsRow reportStats = repository.getReportStats(FROM, TO);

		assertThat(reportStats.reportCount()).isEqualTo(2);
		assertThat(reportStats.aiReviewedCount()).isEqualTo(1);
		assertThat(reportStats.confirmedCount()).isEqualTo(1);
		assertThat(reportStats.dismissedCount()).isEqualTo(1);
		assertThat(repository.countSanctions(FROM, TO)).isEqualTo(2);
	}

	private void createSchema() {
		jdbcTemplate.execute("""
			DO $$
			BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'report_status') THEN
					CREATE TYPE report_status AS ENUM ('pending', 'ai_reviewed', 'confirmed', 'dismissed');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'ai_job_status') THEN
					CREATE TYPE ai_job_status AS ENUM ('pending', 'processing', 'retry', 'completed', 'failed');
				END IF;
			END
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS users (
				user_id BIGINT PRIMARY KEY,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS login_logs (
				log_id BIGINT PRIMARY KEY,
				user_id BIGINT NOT NULL,
				logged_in_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS user_sanctions (
				sanction_id BIGINT PRIMARY KEY,
				user_id BIGINT NOT NULL,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS pins (
				pin_id BIGINT PRIMARY KEY,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS questions (
				question_id BIGINT PRIMARY KEY,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS meetings (
				meeting_id BIGINT PRIMARY KEY,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS answers (
				answer_id BIGINT PRIMARY KEY,
				is_accepted BOOLEAN NOT NULL,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS messages (
				message_id BIGINT PRIMARY KEY,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS reports (
				report_id BIGINT PRIMARY KEY,
				status report_status NOT NULL,
				ai_review_state ai_job_status NOT NULL,
				ai_reviewed_at TIMESTAMPTZ,
				resolved_at TIMESTAMPTZ,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
	}

	private void truncateTables() {
		jdbcTemplate.execute("""
			TRUNCATE TABLE users, login_logs, user_sanctions, pins, questions, meetings,
				answers, messages, reports
			""");
	}

	private void insertRows() {
		jdbcTemplate.update("INSERT INTO users(user_id, created_at) VALUES (1, '2026-07-01T00:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO users(user_id, created_at) VALUES (2, '2026-07-31T23:59:59+09:00')");
		jdbcTemplate.update("INSERT INTO users(user_id, created_at) VALUES (3, '2026-08-01T00:00:00+09:00')");

		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, logged_in_at) VALUES (1, 1, '2026-07-03T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, logged_in_at) VALUES (2, 1, '2026-07-04T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, logged_in_at) VALUES (3, 2, '2026-07-05T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, logged_in_at) VALUES (4, 3, '2026-08-01T00:00:00+09:00')");

		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, created_at) VALUES (1, 1, '2026-07-10T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, created_at) VALUES (2, 1, '2026-07-11T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, created_at) VALUES (3, 2, '2026-08-01T00:00:00+09:00')");

		jdbcTemplate.update("INSERT INTO pins(pin_id, created_at) VALUES (1, '2026-07-02T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO pins(pin_id, created_at) VALUES (2, '2026-08-01T00:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO questions(question_id, created_at) VALUES (1, '2026-07-02T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO meetings(meeting_id, created_at) VALUES (1, '2026-07-02T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO messages(message_id, created_at) VALUES (1, '2026-07-02T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO answers(answer_id, is_accepted, created_at) VALUES (1, true, '2026-07-02T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO answers(answer_id, is_accepted, created_at) VALUES (2, false, '2026-07-03T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO answers(answer_id, is_accepted, created_at) VALUES (3, true, '2026-08-01T00:00:00+09:00')");

		jdbcTemplate.update("""
			INSERT INTO reports(report_id, status, ai_review_state, ai_reviewed_at, resolved_at, created_at)
			VALUES (1, 'confirmed', 'completed', '2026-07-04T10:00:00+09:00', '2026-07-06T10:00:00+09:00', '2026-06-30T10:00:00+09:00')
			""");
		jdbcTemplate.update("""
			INSERT INTO reports(report_id, status, ai_review_state, ai_reviewed_at, resolved_at, created_at)
			VALUES (2, 'dismissed', 'pending', NULL, '2026-07-07T10:00:00+09:00', '2026-07-03T10:00:00+09:00')
			""");
		jdbcTemplate.update("""
			INSERT INTO reports(report_id, status, ai_review_state, ai_reviewed_at, resolved_at, created_at)
			VALUES (3, 'pending', 'completed', '2026-08-01T00:00:00+09:00', NULL, '2026-07-04T10:00:00+09:00')
			""");
	}
}
