package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcQuestionCompletionCallbackRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_ai_question_callback";
	private static final AtomicLong SEQUENCE = new AtomicLong();

	private JdbcClient jdbc;
	private QuestionCompletionCallbackRepository repository;

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
		repository = new JdbcQuestionCompletionCallbackRepository(jdbc);
	}

	@Test
	void readsOnlyAnswerBearingAckPendingCompletedTasksInBoundedCompletionOrderWithoutWritingAck() {
		OffsetDateTime base = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		List<Long> eligible = new ArrayList<>();
		for (int index = 0; index < 35; index++) {
			long questionId = insertQuestion();
			long answerId = insertAiAnswer(questionId);
			insertCompletedTask(questionId, answerId, base.plusSeconds(index), false);
			eligible.add(questionId);
		}
		long ackedQuestionId = insertQuestion();
		insertCompletedTask(ackedQuestionId, insertAiAnswer(ackedQuestionId), base.minusSeconds(2), true);
		long insufficientQuestionId = insertQuestion();
		insertInsufficientTask(insufficientQuestionId, base.minusSeconds(1));

		List<Long> recovered = repository.findPendingQuestionIds(32);
		PendingQuestionCompletion pending = repository.findPending(eligible.getFirst()).orElseThrow();

		assertThat(recovered).containsExactlyElementsOf(eligible.subList(0, 32));
		assertThat(recovered).doesNotContain(ackedQuestionId, insufficientQuestionId);
		assertThat(pending.questionId()).isEqualTo(eligible.getFirst());
		assertThat(pending.answerId()).isPositive();
		assertThat(repository.findPending(ackedQuestionId)).isEmpty();
		assertThat(repository.findPending(insufficientQuestionId)).isEmpty();
		assertThat(repository.existsByQuestionId(eligible.getFirst())).isTrue();
		assertThat(answerAck(eligible.getFirst())).isNull();
	}

	@Test
	void reportsThatACascadeDeletedTaskNoLongerExists() {
		long questionId = insertQuestion();
		long answerId = insertAiAnswer(questionId);
		insertCompletedTask(questionId, answerId, OffsetDateTime.now(), false);

		jdbc.sql("DELETE FROM questions WHERE question_id = :questionId")
			.param("questionId", questionId)
			.update();

		assertThat(repository.existsByQuestionId(questionId)).isFalse();
		assertThat(repository.findPending(questionId)).isEmpty();
	}

	private long insertQuestion() {
		long sequence = SEQUENCE.incrementAndGet();
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "callback-" + sequence + "@example.com")
			.param("nickname", "callback-" + sequence)
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
			RETURNING pin_id
			""")
			.param("userId", userId)
			.query(Long.class)
			.single();
		return jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, :userId, 'title', 'content')
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.query(Long.class)
			.single();
	}

	private long insertAiAnswer(long questionId) {
		return jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (:questionId, NULL, TRUE, 'grounded answer')
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.query(Long.class)
			.single();
	}

	private void insertCompletedTask(
		long questionId,
		long answerId,
		OffsetDateTime completedAt,
		boolean acknowledged
	) {
		jdbc.sql("""
			INSERT INTO ai_question_tasks (
				question_id, status, embedding, embedding_model, answer_id, answer_outcome,
				generation_provider, generation_model, grounding_status, evidence, completed_at,
				answer_notification_processed_at
			)
			VALUES (
				:questionId, 'completed', array_fill(0.0::real, ARRAY[768])::vector,
				'gemini-embedding-2', :answerId, 'local_grounded', 'bedrock', 'nova-micro',
				'grounded', '[{\"sourceId\":1}]'::jsonb, :completedAt,
				CASE WHEN :acknowledged THEN :completedAt ELSE NULL END
			)
			""")
			.param("questionId", questionId)
			.param("answerId", answerId)
			.param("completedAt", completedAt)
			.param("acknowledged", acknowledged)
			.update();
	}

	private void insertInsufficientTask(long questionId, OffsetDateTime completedAt) {
		jdbc.sql("""
			INSERT INTO ai_question_tasks (
				question_id, status, embedding, embedding_model, answer_outcome,
				grounding_status, completed_at
			)
			VALUES (
				:questionId, 'completed', array_fill(0.0::real, ARRAY[768])::vector,
				'gemini-embedding-2', 'insufficient_evidence', 'insufficient_evidence', :completedAt
			)
			""")
			.param("questionId", questionId)
			.param("completedAt", completedAt)
			.update();
	}

	private Object answerAck(long questionId) {
		return jdbc.sql("""
			SELECT answer_notification_processed_at
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query()
			.singleRow()
			.get("answer_notification_processed_at");
	}
}
