package shinhan.fibri.ieum.main.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcAiQuestionAnswerCompletionRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_ai_completion";

	private JdbcClient jdbc;
	private JdbcAiQuestionAnswerCompletionRepository repository;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"))
			.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)")
			.update();
	}

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		repository = new JdbcAiQuestionAnswerCompletionRepository(jdbc);
	}

	@Test
	void readsCompletedTicketAndActiveTargetThenAcknowledgesOnce() {
		Fixture fixture = insertCompletedFixture(false, false);

		assertThat(repository.lockTicket(fixture.questionId())).get().satisfies(ticket -> {
			assertThat(ticket.status()).isEqualTo("completed");
			assertThat(ticket.answerId()).isEqualTo(fixture.answerId());
			assertThat(ticket.notificationProcessedAt()).isNull();
		});
		assertThat(repository.lockQuestion(fixture.questionId())).get().satisfies(question -> {
			assertThat(question.pinId()).isEqualTo(fixture.pinId());
			assertThat(question.recipientUserId()).isEqualTo(fixture.userId());
			assertThat(question.deleted()).isFalse();
		});
		assertThat(repository.lockPin(fixture.pinId())).get().satisfies(pin ->
			assertThat(pin.deleted()).isFalse()
		);
		assertThat(repository.isMatchingAiAnswer(fixture.questionId(), fixture.answerId())).isTrue();
		assertThat(repository.acknowledgeNotification(fixture.questionId(), fixture.answerId())).isEqualTo(1);
		assertThat(repository.acknowledgeNotification(fixture.questionId(), fixture.answerId())).isZero();
		assertThat(jdbc.sql("SELECT answer_notification_processed_at FROM ai_question_tasks WHERE question_id = :id")
			.param("id", fixture.questionId())
			.query(OffsetDateTime.class)
			.optional()).isPresent();
	}

	@Test
	void deletionStateRemainsVisibleWhileRowsAreLockable() {
		Fixture fixture = insertCompletedFixture(true, true);

		assertThat(repository.lockQuestion(fixture.questionId())).get()
			.extracting(AiQuestionAnswerCompletionRepository.LockedQuestion::deleted)
			.isEqualTo(true);
		assertThat(repository.lockPin(fixture.pinId())).get()
			.extracting(AiQuestionAnswerCompletionRepository.LockedPin::deleted)
			.isEqualTo(true);
	}

	@Test
	void deletingTheNotificationDoesNotClearTheTicketAcknowledgement() {
		Fixture fixture = insertCompletedFixture(false, false);
		assertThat(repository.acknowledgeNotification(fixture.questionId(), fixture.answerId())).isEqualTo(1);
		jdbc.sql("""
			INSERT INTO notifications (user_id, type, title, event_key)
			VALUES (:userId, 'question', 'AI answer', :eventKey)
			""")
			.param("userId", fixture.userId())
			.param("eventKey", "answer-created:" + fixture.answerId())
			.update();
		jdbc.sql("DELETE FROM notifications WHERE event_key = :eventKey")
			.param("eventKey", "answer-created:" + fixture.answerId())
			.update();

		assertThat(repository.lockTicket(fixture.questionId())).get()
			.extracting(AiQuestionAnswerCompletionRepository.LockedTicket::notificationProcessedAt)
			.isNotNull();
		assertThat(repository.acknowledgeNotification(fixture.questionId(), fixture.answerId())).isZero();
	}

	private Fixture insertCompletedFixture(boolean questionDeleted, boolean pinDeleted) {
		String suffix = UUID.randomUUID().toString();
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "completion-" + suffix + "@example.com")
			.param("nickname", "complete-" + suffix.substring(0, 8))
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address, deleted_at)
			VALUES (
				:userId,
				'question',
				ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography,
				'Seoul',
				:deletedAt
			)
			RETURNING pin_id
			""")
			.param("userId", userId)
			.param("deletedAt", pinDeleted ? OffsetDateTime.now() : null)
			.query(Long.class)
			.single();
		long questionId = jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content, deleted_at)
			VALUES (:pinId, :userId, 'question title', 'question content', :deletedAt)
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.param("deletedAt", questionDeleted ? OffsetDateTime.now() : null)
			.query(Long.class)
			.single();
		long answerId = jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (:questionId, NULL, TRUE, 'AI answer')
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.query(Long.class)
			.single();
		jdbc.sql("""
			INSERT INTO ai_question_tasks (
				question_id,
				status,
				stage,
				embedding,
				embedding_model,
				answer_id,
				answer_outcome,
				generation_provider,
				generation_model,
				grounding_status,
				evidence,
				completed_at
			)
			VALUES (
				:questionId,
				'completed',
				'persisting',
				array_fill(0.0::real, ARRAY[768])::vector,
				'gemini-embedding-2',
				:answerId,
				'local_grounded',
				'test',
				'test-model',
				'grounded',
				'[{"source":"test"}]'::jsonb,
				CURRENT_TIMESTAMP
			)
			""")
			.param("questionId", questionId)
			.param("answerId", answerId)
			.update();
		return new Fixture(userId, pinId, questionId, answerId);
	}

	private record Fixture(long userId, long pinId, long questionId, long answerId) {
	}
}
