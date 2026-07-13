package shinhan.fibri.ieum.ai.question.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class QuestionCheckpointSchemaIntegrationTest {

	private static final String CANONICAL_DATABASE = "ieum_ai_checkpoint_schema";
	private static final String MIGRATION_DATABASE = "ieum_ai_checkpoint_v17";

	@AfterAll
	static void cleanUpDatabases() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + CANONICAL_DATABASE + " WITH (FORCE)").update();
		admin.sql("DROP DATABASE IF EXISTS " + MIGRATION_DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void canonicalSchemaProvidesValidatedNonblankAnalysisVersion() {
		CanonicalPostgresContainer.recreateDatabase(CANONICAL_DATABASE);
		SqlScriptRunner.run(CANONICAL_DATABASE, "schema.sql");
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(CANONICAL_DATABASE));
		long questionId = insertPendingTask(jdbc, "canonical");

		assertThat(columnExists(jdbc, "analysis_version")).isTrue();
		assertThat(constraintValidated(jdbc, "ck_ai_question_tasks_analysis_version")).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM ai_question_tasks WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(Integer.class)
			.single()).isOne();
		assertThat(jdbc.sql("""
			UPDATE ai_question_tasks
			SET analysis_version = 'query-analysis-v1'
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.update()).isOne();
		assertThatThrownBy(() -> jdbc.sql("""
			UPDATE ai_question_tasks
			SET analysis_version = '   '
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.update())
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("ck_ai_question_tasks_analysis_version");
	}

	@Test
	void v17MigrationAddsCheckpointVersionWithoutChangingExistingTickets() {
		CanonicalPostgresContainer.recreateDatabase(MIGRATION_DATABASE);
		SqlScriptRunner.run(
			MIGRATION_DATABASE,
			"test-baselines/schema-v12.sql",
			"migrations/v13_app_ai_v2_expand.sql",
			"migrations/v14_report_worklist_expand.sql",
			"migrations/v15_question_ai_ticket_notification.sql",
			"migrations/v16_question_ai_finalization_lock.sql"
		);
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(MIGRATION_DATABASE));
		long questionId = insertPendingTask(jdbc, "migration");

		SqlScriptRunner.run(MIGRATION_DATABASE, "migrations/v17_question_ai_checkpoints.sql");

		assertThat(columnExists(jdbc, "analysis_version")).isTrue();
		assertThat(constraintValidated(jdbc, "ck_ai_question_tasks_analysis_version")).isTrue();
		assertThat(jdbc.sql("""
			SELECT analysis_version
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query(String.class)
			.optional()).isEmpty();
	}

	private long insertPendingTask(JdbcClient jdbc, String suffix) {
		long userId = jdbc.sql("""
			INSERT INTO users(email, nickname)
			VALUES (:email, :nickname)
			RETURNING user_id
			""")
			.param("email", suffix + "-" + UUID.randomUUID() + "@example.com")
			.param("nickname", suffix + "-" + UUID.randomUUID())
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins(author_id, pin_type, location, address)
			VALUES (:userId, 'question', ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326), '서울특별시 종로구')
			RETURNING pin_id
			""")
			.param("userId", userId)
			.query(Long.class)
			.single();
		long questionId = jdbc.sql("""
			INSERT INTO questions(pin_id, author_id, title, content)
			VALUES (:pinId, :userId, 'title', 'content')
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO ai_question_tasks(question_id) VALUES (:questionId)")
			.param("questionId", questionId)
			.update();
		return questionId;
	}

	private boolean columnExists(JdbcClient jdbc, String columnName) {
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM information_schema.columns
			    WHERE table_schema = 'public'
			      AND table_name = 'ai_question_tasks'
			      AND column_name = :columnName
			)
			""")
			.param("columnName", columnName)
			.query(Boolean.class)
			.single();
	}

	private boolean constraintValidated(JdbcClient jdbc, String constraintName) {
		return jdbc.sql("""
			SELECT convalidated
			FROM pg_constraint
			WHERE conrelid = 'public.ai_question_tasks'::regclass
			  AND conname = :constraintName
			""")
			.param("constraintName", constraintName)
			.query(Boolean.class)
			.single();
	}
}
