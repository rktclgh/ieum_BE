package shinhan.fibri.ieum.main.answer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class V23MultiAcceptedAnswersMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v23_multi_accepted_answers";

	private JdbcClient jdbc;

	@BeforeEach
	void recreateDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void canonicalSchemaAllowsMultipleAcceptedAnswersForOneQuestion() {
		SqlScriptRunner.run(DATABASE, "schema.sql");
		seedResolvedQuestionWithAcceptedAnswerAndDriftedCounts();

		insertHumanAnswer(100, 1, true, "second accepted human answer");

		assertThat(indexExists("uidx_accepted_answer")).isFalse();
		assertThat(indexExists("idx_answers_accepted_question")).isTrue();
		assertThat(acceptedAnswerCount(100)).isEqualTo(2);
	}

	@Test
	void v23AndV24MigrationsPreserveAnswersAndReconcileAcceptedCountsAndGrades() {
		loadV22Schema();
		long acceptedAnswerId = seedResolvedQuestionWithAcceptedAnswerAndDriftedCounts();
		long aiAnswerId = insertAiAnswer(100, false, "ai answer");
		long pendingAnswerId = insertHumanAnswer(100, 2, false, "pending human answer");

		assertThat(indexExists("uidx_accepted_answer")).isTrue();
		assertThat(indexExists("idx_answers_accepted_question")).isFalse();

		SqlScriptRunner.run(DATABASE, "migrations/v23_multi_accepted_answers.sql");

		assertThat(indexExists("uidx_accepted_answer")).isTrue();
		assertThat(indexExists("idx_answers_accepted_question")).isFalse();

		SqlScriptRunner.run(DATABASE, "migrations/v24_multi_accepted_answers_indexes.sql");

		assertThat(indexExists("uidx_accepted_answer")).isFalse();
		assertThat(indexExists("idx_answers_accepted_question")).isTrue();
		assertThat(answerExists(acceptedAnswerId)).isTrue();
		assertThat(answerExists(aiAnswerId)).isTrue();
		assertThat(answerExists(pendingAnswerId)).isTrue();
		assertThat(userCountAndGrade(1)).containsEntry("accepted_count", 1).containsEntry("grade", "bronze");
		assertThat(userCountAndGrade(2)).containsEntry("accepted_count", 0).containsEntry("grade", "bronze");

		insertHumanAnswer(100, 1, true, "second accepted human answer");
		assertThat(acceptedAnswerCount(100)).isEqualTo(2);
	}

	@Test
	void v23MigrationRejectsAcceptedAnswersOnUnresolvedQuestions() {
		loadV22Schema();
		seedUnresolvedQuestionWithAcceptedAnswer();

		assertThatThrownBy(() -> SqlScriptRunner.run(DATABASE, "migrations/v23_multi_accepted_answers.sql"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("accepted answer exists on unresolved question");
	}

	@Test
	void v23MigrationRejectsResolvedQuestionsWithoutAcceptedAnswers() {
		loadV22Schema();
		seedResolvedQuestionWithoutAcceptedAnswer();

		assertThatThrownBy(() -> SqlScriptRunner.run(DATABASE, "migrations/v23_multi_accepted_answers.sql"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("resolved question has no accepted answer");
	}

	private void loadV22Schema() {
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
	}

	private long seedResolvedQuestionWithAcceptedAnswerAndDriftedCounts() {
		seedUsers();
		seedQuestion(100, 1, true);
		jdbc.sql("""
			UPDATE users
			SET accepted_count = 6, grade = 'silver'
			WHERE user_id = 1
			""").update();
		jdbc.sql("""
			UPDATE users
			SET accepted_count = 31, grade = 'platinum'
			WHERE user_id = 2
			""").update();
		return insertHumanAnswer(100, 1, true, "accepted human answer");
	}

	private void seedUnresolvedQuestionWithAcceptedAnswer() {
		seedUsers();
		seedQuestion(100, 1, false);
		insertHumanAnswer(100, 1, true, "accepted on unresolved question");
	}

	private void seedResolvedQuestionWithoutAcceptedAnswer() {
		seedUsers();
		seedQuestion(100, 1, true);
		insertHumanAnswer(100, 1, false, "unaccepted answer");
	}

	private void seedUsers() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status)
			VALUES
			    (1, 'answerer@example.com', 'email', 'hash', 'answerer', true, 'user', 'active'),
			    (2, 'owner@example.com', 'email', 'hash', 'owner', true, 'user', 'active')
			""").update();
	}

	private void seedQuestion(long questionId, long authorId, boolean resolved) {
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:authorId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울')
			RETURNING pin_id
			""").param("authorId", authorId).query(Long.class).single();
		jdbc.sql("""
			INSERT INTO questions (question_id, pin_id, author_id, title, content, is_resolved)
			VALUES (:questionId, :pinId, :authorId, 'question', 'question content', :resolved)
			""")
			.param("questionId", questionId)
			.param("pinId", pinId)
			.param("authorId", authorId)
			.param("resolved", resolved)
			.update();
	}

	private long insertHumanAnswer(long questionId, long authorId, boolean accepted, String content) {
		return jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content, is_accepted)
			VALUES (:questionId, :authorId, false, :content, :accepted)
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.param("authorId", authorId)
			.param("content", content)
			.param("accepted", accepted)
			.query(Long.class)
			.single();
	}

	private long insertAiAnswer(long questionId, boolean accepted, String content) {
		return jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content, is_accepted)
			VALUES (:questionId, NULL, true, :content, :accepted)
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.param("content", content)
			.param("accepted", accepted)
			.query(Long.class)
			.single();
	}

	private boolean indexExists(String indexName) {
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM pg_indexes
			    WHERE schemaname = 'public' AND indexname = :indexName
			)
			""").param("indexName", indexName).query(Boolean.class).single();
	}

	private int acceptedAnswerCount(long questionId) {
		return jdbc.sql("""
			SELECT count(*)::integer
			FROM answers
			WHERE question_id = :questionId AND is_accepted
			""").param("questionId", questionId).query(Integer.class).single();
	}

	private boolean answerExists(long answerId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM answers WHERE answer_id = :answerId)")
			.param("answerId", answerId)
			.query(Boolean.class)
			.single();
	}

	private Map<String, Object> userCountAndGrade(long userId) {
		return jdbc.sql("""
			SELECT accepted_count, grade::text AS grade
			FROM users
			WHERE user_id = :userId
			""").param("userId", userId).query().singleRow();
	}
}
