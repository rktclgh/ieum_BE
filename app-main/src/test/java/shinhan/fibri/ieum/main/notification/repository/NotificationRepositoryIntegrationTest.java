package shinhan.fibri.ieum.main.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class NotificationRepositoryIntegrationTest {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres")
	).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
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
	private NotificationRepository notificationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchema() {
		jdbcTemplate.execute("""
			DO $$
			BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'notification_type') THEN
					CREATE TYPE notification_type AS ENUM ('meeting', 'question', 'chat', 'friend', 'location', 'system');
				END IF;
			END
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS users (
				user_id BIGINT PRIMARY KEY
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS pins (
				pin_id BIGINT PRIMARY KEY,
				author_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS questions (
				question_id BIGINT PRIMARY KEY,
				pin_id BIGINT NOT NULL REFERENCES pins(pin_id) ON DELETE CASCADE,
				author_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS answers (
				answer_id BIGINT PRIMARY KEY,
				question_id BIGINT NOT NULL REFERENCES questions(question_id) ON DELETE CASCADE,
				is_ai BOOLEAN NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS notifications (
				notification_id BIGSERIAL PRIMARY KEY,
				user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
				type notification_type NOT NULL,
				title VARCHAR(200) NOT NULL,
				body TEXT,
				ref_id BIGINT,
				answer_is_ai BOOLEAN,
				event_key VARCHAR(120),
				is_read BOOLEAN NOT NULL DEFAULT FALSE,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				CONSTRAINT ck_notifications_answer_is_ai
					CHECK (answer_is_ai IS NULL OR type = 'question'::notification_type)
			)
			""");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notif_user ON notifications(user_id, created_at DESC)");
		jdbcTemplate.execute("""
			CREATE UNIQUE INDEX IF NOT EXISTS uidx_notifications_user_event_key
			ON notifications(user_id, event_key)
			WHERE event_key IS NOT NULL
			""");
		jdbcTemplate.execute("TRUNCATE TABLE notifications, answers, questions, pins, users RESTART IDENTITY CASCADE");
		jdbcTemplate.update("INSERT INTO users (user_id) VALUES (1), (2)");
	}

	@Test
	void savesPostgresEnumWithDatabaseGeneratedCreatedAt() {
		Notification notification = notificationRepository.saveAndFlush(Notification.of(
			1L,
			NotificationType.question,
			"새 답변",
			"질문에 답변이 달렸어요",
			50L,
			false
		));

		assertThat(notification.getId()).isNotNull();
		assertThat(notification.getCreatedAt()).isNotNull();
		assertThat(notification.isRead()).isFalse();
		assertThat(notification.getAnswerIsAi()).isFalse();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT answer_is_ai FROM notifications WHERE notification_id = ?",
			Boolean.class,
			notification.getId()
		)).isFalse();
	}

	@Test
	void findsOnlyOwnerNotificationsAfterCreatedAtAndIdCursor() {
		OffsetDateTime older = OffsetDateTime.parse("2026-07-01T10:00:00+09:00");
		OffsetDateTime tied = OffsetDateTime.parse("2026-07-02T10:00:00+09:00");
		OffsetDateTime newer = OffsetDateTime.parse("2026-07-03T10:00:00+09:00");

		insertNotification(1L, "older", older);
		long tiedEarlierId = insertNotification(1L, "tied-earlier", tied);
		long cursorId = insertNotification(1L, "cursor", tied);
		insertNotification(1L, "newer", newer);
		insertNotification(2L, "other-user", newer.plusHours(1));

		List<Notification> page = notificationRepository.findPage(
			1L,
			tied,
			cursorId,
			PageRequest.of(0, 10)
		);

		assertThat(page).extracting(Notification::getTitle).containsExactly("tied-earlier", "older");
		assertThat(page).extracting(Notification::getId).containsExactly(tiedEarlierId, 1L);
	}

	@Test
	void findsFirstPageSortedForOwner() {
		OffsetDateTime older = OffsetDateTime.parse("2026-07-01T10:00:00+09:00");
		OffsetDateTime newer = OffsetDateTime.parse("2026-07-02T10:00:00+09:00");

		insertNotification(1L, "older", older);
		insertNotification(1L, "newer", newer);
		insertNotification(2L, "other-user", newer.plusHours(1));

		List<Notification> page = notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(
			1L,
			PageRequest.of(0, 10)
		);

		assertThat(page).extracting(Notification::getTitle).containsExactly("newer", "older");
	}

	@Test
	void countsOnlyUnreadNotificationsForOwner() {
		insertNotification(1L, "unread", OffsetDateTime.parse("2026-07-01T10:00:00+09:00"));
		long readId = insertNotification(1L, "read", OffsetDateTime.parse("2026-07-02T10:00:00+09:00"));
		insertNotification(2L, "other-user", OffsetDateTime.parse("2026-07-03T10:00:00+09:00"));
		jdbcTemplate.update("UPDATE notifications SET is_read = true WHERE notification_id = ?", readId);

		assertThat(notificationRepository.countUnreadByUserId(1L)).isEqualTo(1L);
	}

	@Test
	void marksReadIdempotentlyAndOnlyForOwner() {
		long notificationId = insertNotification(1L, "mine", OffsetDateTime.parse("2026-07-01T10:00:00+09:00"));
		long otherUserNotificationId = insertNotification(2L, "other", OffsetDateTime.parse("2026-07-02T10:00:00+09:00"));

		assertThat(notificationRepository.markReadByIdAndUserId(notificationId, 1L)).isEqualTo(1);
		assertThat(notificationRepository.markReadByIdAndUserId(notificationId, 1L)).isEqualTo(1);
		assertThat(notificationRepository.markReadByIdAndUserId(otherUserNotificationId, 1L)).isZero();
		assertThat(notificationRepository.countUnreadByUserId(1L)).isZero();
		assertThat(notificationRepository.countUnreadByUserId(2L)).isEqualTo(1L);
	}

	@Test
	void marksAllUnreadAndDeletesOnlyOwnedNotifications() {
		long readId = insertNotification(1L, "read", OffsetDateTime.parse("2026-07-01T10:00:00+09:00"));
		insertNotification(1L, "unread", OffsetDateTime.parse("2026-07-02T10:00:00+09:00"));
		long otherUserNotificationId = insertNotification(2L, "other", OffsetDateTime.parse("2026-07-03T10:00:00+09:00"));
		jdbcTemplate.update("UPDATE notifications SET is_read = true WHERE notification_id = ?", readId);

		assertThat(notificationRepository.markAllRead(1L)).isEqualTo(1);
		assertThat(notificationRepository.countUnreadByUserId(1L)).isZero();
		assertThat(notificationRepository.deleteByIdAndUserId(readId, 1L)).isEqualTo(1);
		assertThat(notificationRepository.deleteByIdAndUserId(readId, 1L)).isZero();
		assertThat(notificationRepository.deleteByIdAndUserId(otherUserNotificationId, 1L)).isZero();
	}

	@Test
	void insertsOnlyOneNotificationForTheSameUserAndEventKey() {
		NotificationEventRepository eventRepository = new NotificationEventRepository(jdbcTemplate);

		var first = eventRepository.insertOnce(
			1L,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			50L,
			true,
			"answer-created:300"
		);
		var duplicate = eventRepository.insertOnce(
			1L,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			50L,
			true,
			"answer-created:300"
		);

		assertThat(first).isPresent();
		assertThat(duplicate).isEmpty();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM notifications WHERE user_id = 1 AND event_key = 'answer-created:300'",
			Long.class
		)).isEqualTo(1L);
		Notification stored = notificationRepository.findById(first.orElseThrow().notificationId()).orElseThrow();
		assertThat(stored.getAnswerIsAi()).isTrue();
		assertThat(stored.getEventKey()).isEqualTo("answer-created:300");
	}

	private long insertNotification(Long userId, String title, OffsetDateTime createdAt) {
		return jdbcTemplate.queryForObject(
			"""
				INSERT INTO notifications (user_id, type, title, is_read, created_at)
				VALUES (?, 'question'::notification_type, ?, false, ?)
				RETURNING notification_id
				""",
			Long.class,
			userId,
			title,
			createdAt
		);
	}

}
