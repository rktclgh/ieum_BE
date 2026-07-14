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
	void userStatsCountUsersAliveAtObservationTimeEvenIfDeletedLater() {
		// signups: user1(7/1)·user2(7/31) 생존, user7(7/10 가입, 8/5 탈퇴=기간 후) 포함,
		//          user5(7/15 가입, 7/20 탈퇴=기간 내) 제외 → 3
		assertThat(repository.countSignups(FROM, TO)).isEqualTo(3);
		// active: user1·user2 생존 로그인, user5(7/6 로그인 < 7/20 탈퇴) 포함,
		//         user6(7/5 탈퇴 후 7/10 로그인) 제외 → 3
		assertThat(repository.countActiveUsers(FROM, TO)).isEqualTo(3);
		// suspended: user1, user5(7/12 제재 < 7/20 탈퇴) 포함,
		//            user6(7/5 탈퇴 후 7/10 제재) 제외 → 2
		assertThat(repository.countSuspendedUsers(FROM, TO)).isEqualTo(2);
	}

	@Test
	void contentStatsCountsCreatedContentAndAcceptedAnswersWithinRange() {
		AnswerStatsRow answerStats = repository.getAnswerStats(FROM, TO);

		assertThat(repository.countPins(FROM, TO)).isEqualTo(2);
		assertThat(repository.countQuestions(FROM, TO)).isEqualTo(1);
		assertThat(repository.countMeetings(FROM, TO)).isEqualTo(1);
		assertThat(repository.countMessages(FROM, TO)).isEqualTo(1);
		assertThat(answerStats.total()).isEqualTo(3);
		assertThat(answerStats.userTotal()).isEqualTo(2);
		assertThat(answerStats.accepted()).isEqualTo(1);
	}

	@Test
	void reportStatsUseSeparateEventTimestampsAndSanctionCountUsesRowsNotDistinctUsers() {
		ReportStatsRow reportStats = repository.getReportStats(FROM, TO);

		assertThat(reportStats.reportCount()).isEqualTo(2);
		assertThat(reportStats.aiReviewedCount()).isEqualTo(1);
		assertThat(reportStats.confirmedCount()).isEqualTo(1);
		assertThat(reportStats.dismissedCount()).isEqualTo(1);
		// sanctionCount는 행 수 지표: 탈퇴 여부 무관, 기간 내 제재 4건(s1·s2·s4·s5)
		assertThat(repository.countSanctions(FROM, TO)).isEqualTo(4);
	}

	private void truncateTables() {
		jdbcTemplate.execute("TRUNCATE TABLE users, chat_rooms RESTART IDENTITY CASCADE");
	}

	private void insertRows() {
		insertUser(1, "user1", "2026-07-01T00:00:00+09:00");
		insertUser(2, "user2", "2026-07-31T23:59:59+09:00");
		insertUser(3, "user3", "2026-08-01T00:00:00+09:00");
		insertUser(4, "admin", "2026-06-01T00:00:00+09:00");
		// user5: 기간 내 가입(7/15) 후 기간 내 탈퇴(7/20) — 가입자 제외, 탈퇴 전 활동은 포함
		insertDeletedUser(5, "deleted", "2026-07-15T00:00:00+09:00", "2026-07-20T00:00:00+09:00");
		// user6: 기간 전 가입, 기간 초(7/5) 탈퇴 — 탈퇴 후 이벤트는 제외돼야 함
		insertDeletedUser(6, "deleted2", "2026-06-10T00:00:00+09:00", "2026-07-05T00:00:00+09:00");
		// user7: 기간 내 가입(7/10), 기간 종료 후 탈퇴(8/5) — 관측 시점(:to) 기준 생존 → 가입자 포함
		insertDeletedUser(7, "deleted3", "2026-07-10T00:00:00+09:00", "2026-08-05T00:00:00+09:00");

		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (1, 1, 'email', '2026-07-03T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (2, 1, 'email', '2026-07-04T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (3, 2, 'email', '2026-07-05T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (4, 3, 'email', '2026-08-01T00:00:00+09:00')");
		// user5: 탈퇴(7/20) 전 로그인 → 활성 포함
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (5, 5, 'email', '2026-07-06T10:00:00+09:00')");
		// user6: 탈퇴(7/5) 후 로그인 → 활성 제외
		jdbcTemplate.update("INSERT INTO login_logs(log_id, user_id, provider, logged_in_at) VALUES (6, 6, 'email', '2026-07-10T10:00:00+09:00')");

		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, admin_id, sanction_type, reason, created_at) VALUES (1, 1, 4, 'permanent', 'reason', '2026-07-10T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, admin_id, sanction_type, reason, created_at) VALUES (2, 1, 4, 'permanent', 'reason', '2026-07-11T10:00:00+09:00')");
		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, admin_id, sanction_type, reason, created_at) VALUES (3, 2, 4, 'permanent', 'reason', '2026-08-01T00:00:00+09:00')");
		// user5: 탈퇴(7/20) 전 제재 → 정지 유저 포함
		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, admin_id, sanction_type, reason, created_at) VALUES (4, 5, 4, 'permanent', 'reason', '2026-07-12T10:00:00+09:00')");
		// user6: 탈퇴(7/5) 후 제재 → 정지 유저 제외 (sanctionCount 행 수에는 포함)
		jdbcTemplate.update("INSERT INTO user_sanctions(sanction_id, user_id, admin_id, sanction_type, reason, created_at) VALUES (5, 6, 4, 'permanent', 'reason', '2026-07-13T10:00:00+09:00')");

		insertPin(1, 1, "question", "2026-07-02T10:00:00+09:00");
		insertPin(2, 1, "question", "2026-08-01T00:00:00+09:00");
		insertPin(3, 1, "meeting", "2026-07-02T10:00:00+09:00");
		insertQuestion(1, 1, 1, "2026-07-02T10:00:00+09:00");
		insertMeeting(1, 3, 1, "2026-07-02T10:00:00+09:00");
		insertChatRoom(1);
		insertMessage(1, 1, 1, "2026-07-02T10:00:00+09:00");
		insertMessage(2, 1, 2, "2026-08-01T00:00:00+09:00");
		insertMessage(3, 1, 3, "2026-08-01T00:00:00+09:00");
		insertMessage(4, 1, 1, "2026-06-01T10:00:00+09:00");
		insertAnswer(1, 1, 2, true, "2026-07-02T10:00:00+09:00");
		insertAnswer(2, 1, 3, false, "2026-07-03T10:00:00+09:00");
		insertAnswer(3, 1, 2, false, "2026-08-01T00:00:00+09:00");
		insertAiAnswer(4, 1, "2026-07-04T10:00:00+09:00");

		jdbcTemplate.update("""
			INSERT INTO reports(
				report_id, reporter_id, target_type, message_id, reported_user_id, reason, context_hash,
				status, resolved_by, ai_review_state, ai_decision, ai_confidence, ai_reason,
				ai_model_version, ai_policy_set_hash, ai_review_result, ai_reviewed_at, resolved_at, created_at
			)
			VALUES (
				1, 2, 'message', 1, 1, 'spam', repeat('a', 64),
				'confirmed', 4, 'completed', 'normal', 0.9500, 'normal content',
				'test-model', repeat('d', 64), '{}'::jsonb,
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
				status, ai_review_state, ai_decision, ai_confidence, ai_reason,
				ai_model_version, ai_policy_set_hash, ai_review_result, ai_reviewed_at, created_at
			)
			VALUES (
				3, 2, 'message', 3, 1, 'etc', repeat('c', 64),
				'pending', 'completed', 'normal', 0.9500, 'normal content',
				'test-model', repeat('e', 64), '{}'::jsonb,
				'2026-08-01T00:00:00+09:00', '2026-07-04T10:00:00+09:00'
			)
			""");
		// 세 이벤트 모두 기간 밖 — 프루닝 WHERE로 걸러져 어떤 지표에도 잡히지 않아야 한다
		jdbcTemplate.update("""
			INSERT INTO reports(
				report_id, reporter_id, target_type, message_id, reported_user_id, reason, context_hash,
				status, ai_review_state, created_at
			)
			VALUES (
				4, 2, 'message', 4, 1, 'spam', repeat('d', 64),
				'pending', 'pending', '2026-06-01T10:00:00+09:00'
			)
			""");
	}

	private void insertUser(long userId, String nickname, String createdAt) {
		jdbcTemplate.update("""
			INSERT INTO users(user_id, email, password_hash, nickname, email_verified, created_at)
			VALUES (?, ?, 'hash', ?, true, ?::timestamptz)
			""", userId, nickname + "@example.com", nickname, createdAt);
	}

	private void insertDeletedUser(long userId, String nickname, String createdAt, String deletedAt) {
		jdbcTemplate.update("""
			INSERT INTO users(user_id, email, password_hash, nickname, email_verified, created_at, deleted_at)
			VALUES (?, ?, 'hash', ?, true, ?::timestamptz, ?::timestamptz)
			""", userId, nickname + "@example.com", nickname, createdAt, deletedAt);
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

	private void insertAiAnswer(long answerId, long questionId, String createdAt) {
		jdbcTemplate.update("""
			INSERT INTO answers(answer_id, question_id, author_id, is_ai, content, is_accepted, created_at)
			VALUES (?, ?, NULL, true, 'ai answer', false, ?::timestamptz)
			""", answerId, questionId, createdAt);
	}
}
