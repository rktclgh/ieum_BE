package shinhan.fibri.ieum.main.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class V15QuestionAiTicketNotificationMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v15_question_ai_notification";

	private JdbcClient jdbc;

	@BeforeEach
	void recreateDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
	}

	@Test
	void upgradesPreV15TicketAndNotificationTables() {
		createPreV15Tables();

		SqlScriptRunner.run(DATABASE, "migrations/v15_question_ai_ticket_notification.sql");

		assertPostV15Shape();
		assertConstraintsAndUniqueEventKey();
	}

	@Test
	void canonicalSchemaHasTheSameV15Shape() {
		SqlScriptRunner.run(DATABASE, "schema.sql");

		assertPostV15Shape();
	}

	@Test
	void rejectsNewCompletedTaskThatUsesTheLegacyEmbeddingModel() {
		createPreV15Tables();

		SqlScriptRunner.run(DATABASE, "migrations/v15_question_ai_ticket_notification.sql");

		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO ai_question_tasks (
				question_id, status, embedding, embedding_model, answer_id, completed_at
			)
			VALUES (
				100, 'completed', array_fill(0.0::real, ARRAY[768])::vector,
				'gemini-embedding-001', 1000, CURRENT_TIMESTAMP
			)
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_ai_question_tasks_embedding_model");
	}

	private void createPreV15Tables() {
		jdbc.sql("CREATE EXTENSION IF NOT EXISTS vector").update();
		jdbc.sql("CREATE TYPE ai_job_status AS ENUM ('pending', 'processing', 'retry', 'completed', 'cancelled', 'dead')")
			.update();
		jdbc.sql("CREATE TYPE notification_type AS ENUM ('meeting', 'question', 'chat', 'friend', 'location', 'system')")
			.update();
		jdbc.sql("""
			CREATE TABLE ai_question_tasks (
				question_id BIGINT PRIMARY KEY,
				status ai_job_status NOT NULL DEFAULT 'pending',
				embedding vector(768),
				embedding_model VARCHAR(100),
				answer_id BIGINT,
				completed_at TIMESTAMPTZ
			)
			""").update();
		jdbc.sql("""
			INSERT INTO ai_question_tasks (
				question_id, status, embedding, embedding_model, answer_id, completed_at
			)
			VALUES
				(98, 'pending', array_fill(0.0::real, ARRAY[768])::vector,
				 'gemini-embedding-001', NULL, NULL),
				(99, 'completed', array_fill(0.0::real, ARRAY[768])::vector,
				 'gemini-embedding-001', 990, CURRENT_TIMESTAMP)
			""").update();
		jdbc.sql("""
			ALTER TABLE ai_question_tasks
			ADD CONSTRAINT ck_ai_question_tasks_embedding_model
			CHECK (embedding_model IS NULL OR embedding_model = 'gemini-embedding-2') NOT VALID
			""").update();
		jdbc.sql("""
			CREATE INDEX idx_ai_question_tasks_embedding_hnsw
			ON ai_question_tasks USING hnsw (embedding vector_cosine_ops)
			WHERE embedding IS NOT NULL
			""").update();
		jdbc.sql("""
			CREATE TABLE notifications (
				notification_id BIGSERIAL PRIMARY KEY,
				user_id BIGINT NOT NULL,
				type notification_type NOT NULL,
				title VARCHAR(200) NOT NULL,
				body TEXT,
				ref_id BIGINT,
				is_read BOOLEAN NOT NULL DEFAULT FALSE,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now()
			)
			""").update();
	}

	private void assertPostV15Shape() {
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'notifications'
			  AND column_name IN ('answer_is_ai', 'event_key')
			""").query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'ai_question_tasks'
			  AND column_name IN (
				  'cancel_requested_at',
				  'answer_notification_processed_at',
				  'legacy_embedding_model_migrated'
			  )
			""").query(Integer.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM pg_constraint
			WHERE conrelid = 'notifications'::regclass
			  AND conname = 'ck_notifications_answer_is_ai'
			  AND convalidated
			""").query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM pg_constraint
			WHERE conrelid = 'ai_question_tasks'::regclass
			  AND conname IN ('ck_ai_question_tasks_embedding_model', 'ck_ai_question_tasks_answer_notification')
			  AND convalidated
			""").query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM pg_indexes
			WHERE schemaname = 'public'
			  AND indexname = 'idx_ai_question_tasks_embedding_hnsw'
			""").query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM pg_indexes
			WHERE schemaname = 'public'
			  AND indexname IN ('idx_ai_question_tasks_pending_notification', 'uidx_notifications_user_event_key')
			""").query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM pg_trigger
			WHERE tgrelid = 'ai_question_tasks'::regclass
			  AND tgname = 'trg_ai_question_tasks_legacy_embedding_marker'
			  AND NOT tgisinternal
			""").query(Integer.class).single()).isEqualTo(1);
	}

	private void assertConstraintsAndUniqueEventKey() {
		assertThat(jdbc.sql("""
			SELECT embedding IS NULL AND embedding_model IS NULL
			FROM ai_question_tasks
			WHERE question_id = 98
			""").query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("""
			SELECT legacy_embedding_model_migrated
			FROM ai_question_tasks
			WHERE question_id = 99
			""").query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("""
			UPDATE ai_question_tasks
			SET answer_notification_processed_at = CURRENT_TIMESTAMP
			WHERE question_id = 99
			""").update()).isOne();
		jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id, embedding_model)
			VALUES (1, 'gemini-embedding-2')
			""").update();
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id, embedding_model)
			VALUES (2, 'gemini-embedding-001')
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_ai_question_tasks_embedding_model");
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO ai_question_tasks (
				question_id, status, embedding, embedding_model, answer_id, completed_at,
				legacy_embedding_model_migrated
			)
			VALUES (
				4, 'completed', array_fill(0.0::real, ARRAY[768])::vector,
				'gemini-embedding-001', 40, CURRENT_TIMESTAMP, true
			)
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_ai_question_tasks_embedding_model");
		assertThatThrownBy(() -> jdbc.sql("""
			UPDATE ai_question_tasks
			SET legacy_embedding_model_migrated = false
			WHERE question_id = 99
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_ai_question_tasks_embedding_model");
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id, status, answer_id, answer_notification_processed_at)
			VALUES (3, 'pending', 30, CURRENT_TIMESTAMP)
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_ai_question_tasks_answer_notification");

		jdbc.sql("""
			INSERT INTO notifications (user_id, type, title, answer_is_ai, event_key)
			VALUES (1, 'question', 'AI answer', true, 'answer-created:10')
			""").update();
		jdbc.sql("""
			INSERT INTO notifications (user_id, type, title, answer_is_ai, event_key)
			VALUES (2, 'question', 'AI answer', true, 'answer-created:10')
			""").update();
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO notifications (user_id, type, title, event_key)
			VALUES (1, 'question', 'duplicate', 'answer-created:10')
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("uidx_notifications_user_event_key");
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO notifications (user_id, type, title, answer_is_ai)
			VALUES (3, 'friend', 'not an answer notification', false)
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_notifications_answer_is_ai");
	}
}
