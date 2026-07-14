package shinhan.fibri.ieum.main.admin.stats.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.AnswerStatsRow;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.ReportStatsRow;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AdminStatsQueryRepository.class)
class AdminStatsQueryRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_admin_stats_query_repository";

	static {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
	}

	private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-07-01T00:00:00+09:00");
	private static final OffsetDateTime TO = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> CanonicalPostgresContainer.jdbcUrl(DATABASE));
		registry.add("spring.datasource.username", CanonicalPostgresContainer::username);
		registry.add("spring.datasource.password", CanonicalPostgresContainer::password);
		registry.add("spring.datasource.driver-class-name", CanonicalPostgresContainer::driverClassName);
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

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUpSchemaAndRows() {
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

		assertThat(repository.countPins(FROM, TO)).isEqualTo(2);
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

	private void truncateTables() {
		jdbcTemplate.execute("TRUNCATE TABLE users, chat_rooms RESTART IDENTITY CASCADE");
	}

	private void insertRows() {
		insertUser(1, "user1", "2026-07-01T00:00:00+09:00");
		insertUser(2, "user2", "2026-07-31T23:59:59+09:00");
		insertUser(3, "user3", "2026-08-01T00:00:00+09:00");
		insertUser(4, "admin", "2026-06-01T00:00:00+09:00");

		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (1, 1, 'email', '2026-07-03T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (2, 1, 'email', '2026-07-04T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (3, 2, 'email', '2026-07-05T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (4, 3, 'email', '2026-08-01T00:00:00+09:00')");

		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, admin_id, sanction_type, reason, created_at) VALUES (1, 1, 4, 'permanent', 'reason', '2026-07-10T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, admin_id, sanction_type, reason, created_at) VALUES (2, 1, 4, 'permanent', 'reason', '2026-07-11T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, admin_id, sanction_type, reason, created_at) VALUES (3, 2, 4, 'permanent', 'reason', '2026-08-01T00:00:00+09:00')");

		insertPin(1, 1, "question", "2026-07-02T10:00:00+09:00");
		insertPin(2, 1, "question", "2026-08-01T00:00:00+09:00");
		insertPin(3, 1, "meeting", "2026-07-02T10:00:00+09:00");
		insertQuestion(1, 1, 1, "2026-07-02T10:00:00+09:00");
		insertMeeting(1, 3, 1, "2026-07-02T10:00:00+09:00");
		insertChatRoom(1);
		insertMessage(1, 1, 1, "2026-07-02T10:00:00+09:00");
		insertMessage(2, 1, 2, "2026-08-01T00:00:00+09:00");
		insertMessage(3, 1, 3, "2026-08-01T00:00:00+09:00");
		insertAnswer(1, 1, 2, true, "2026-07-02T10:00:00+09:00");
		insertAnswer(2, 1, 3, false, "2026-07-03T10:00:00+09:00");
		insertAnswer(3, 1, 2, false, "2026-08-01T00:00:00+09:00");

		jdbcTemplate.update("""
			INSERT INTO reports(
				report_id, reporter_id, target_type, message_id, reported_user_id, reason, context_hash,
				status, resolved_by, ai_review_state, ai_decision, ai_review_result, ai_reviewed_at, resolved_at, created_at
			)
			VALUES (
				1, 2, 'message', 1, 1, 'spam', repeat('a', 64),
				'confirmed', 4, 'completed', 'normal', '{}'::jsonb,
				'2026-07-04T10:00:00+09:00', '2026-07-06T10:00:00+09:00', '2026-06-30T10:00:00+09:00'
			)
			""");
		jdbcTemplate.update("""
			INSERT INTO reports(
				report_id, reporter_id, target_type, message_id, reported_user_id, reason, context_hash,
				status, resolved_by, ai_review_state, resolved_at, created_at
			)
			VALUES (
				2, 2, 'message', 2, 1, 'abuse', repeat('b', 64),
				'dismissed', 4, 'pending', '2026-07-07T10:00:00+09:00', '2026-07-03T10:00:00+09:00'
			)
			""");
		jdbcTemplate.update("""
			INSERT INTO reports(
				report_id, reporter_id, target_type, message_id, reported_user_id, reason, context_hash,
				status, ai_review_state, ai_decision, ai_review_result, ai_reviewed_at, created_at
			)
			VALUES (
				3, 2, 'message', 3, 1, 'etc', repeat('c', 64),
				'pending', 'completed', 'normal', '{}'::jsonb, '2026-08-01T00:00:00+09:00', '2026-07-04T10:00:00+09:00'
			)
			""");
	}

	private void insertUser(long userId, String nickname, String createdAt) {
		jdbcTemplate.update("""
			INSERT INTO users(user_id, email, password_hash, nickname, email_verified, created_at)
			VALUES (?, ?, 'hash', ?, true, ?::timestamptz)
			""", userId, nickname + "@example.com", nickname, createdAt);
	}

	private void insertPin(long pinId, long authorId, String pinType, String createdAt) {
		jdbcTemplate.update("""
			INSERT INTO pins(pin_id, author_id, pin_type, location, address, created_at)
			VALUES (?, ?, ?::pin_type, ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울특별시', ?::timestamptz)
			""", pinId, authorId, pinType, createdAt);
	}

	private void insertQuestion(long questionId, long pinId, long authorId, String createdAt) {
		jdbcTemplate.update("""
			INSERT INTO questions(question_id, pin_id, author_id, title, content, created_at)
			VALUES (?, ?, ?, 'title', 'content', ?::timestamptz)
			""", questionId, pinId, authorId, createdAt);
	}

	private void insertMeeting(long meetingId, long pinId, long hostId, String createdAt) {
		jdbcTemplate.update("""
			INSERT INTO meetings(meeting_id, pin_id, host_id, title, content, meeting_at, created_at)
			VALUES (?, ?, ?, 'meeting', 'content', ?::timestamptz, ?::timestamptz)
			""", meetingId, pinId, hostId, createdAt, createdAt);
	}

	private void insertChatRoom(long roomId) {
		jdbcTemplate.update("""
			INSERT INTO chat_rooms(room_id, room_type, room_key)
			VALUES (?, 'direct', 'd:1:2')
			""", roomId);
	}

	private void insertMessage(long messageId, long roomId, long senderId, String createdAt) {
		jdbcTemplate.update("""
			INSERT INTO messages(message_id, room_id, sender_id, content, created_at)
			VALUES (?, ?, ?, 'message', ?::timestamptz)
			""", messageId, roomId, senderId, createdAt);
	}

	private void insertAnswer(long answerId, long questionId, long authorId, boolean accepted, String createdAt) {
		jdbcTemplate.update("""
			INSERT INTO answers(answer_id, question_id, author_id, is_ai, content, is_accepted, created_at)
			VALUES (?, ?, ?, false, 'answer', ?, ?::timestamptz)
			""", answerId, questionId, authorId, accepted, createdAt);
	}
}
