package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

/**
 * {@link JdbcQuestionCompletionCallbackRepository#findPendingBatch(int)}. 브리프 "먼저 쓸 테스트" 2번,
 * spec.md §5.4/§8.5.
 */
class JdbcQuestionCompletionCallbackRepositoryBatchTest {

	private static final String DATABASE = "ieum_ai_question_callback_batch";
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
	void returnsOnlyAckPendingCompletedTasksUpToTheLimit() {
		OffsetDateTime base = OffsetDateTime.parse("2026-07-01T00:00:00Z");
		long q1 = insertQuestion();
		long a1 = insertAiAnswer(q1);
		insertCompletedTask(q1, a1, base, false);
		long q2 = insertQuestion();
		long a2 = insertAiAnswer(q2);
		insertCompletedTask(q2, a2, base.plusSeconds(1), false);
		long q3 = insertQuestion();
		long a3 = insertAiAnswer(q3);
		insertCompletedTask(q3, a3, base.plusSeconds(2), false);

		// 이미 ACK 된 row — 반환되면 안 된다.
		long ackedQuestionId = insertQuestion();
		insertCompletedTask(ackedQuestionId, insertAiAnswer(ackedQuestionId), base.minusSeconds(1), true);
		// insufficient_evidence(answer_id NULL) — 반환되면 안 된다.
		insertInsufficientTask(insertQuestion(), base.minusSeconds(2));

		List<PendingQuestionCompletion> batch = repository.findPendingBatch(2);

		assertThat(batch).hasSize(2);
		assertThat(batch).extracting(PendingQuestionCompletion::questionId)
			.doesNotContain(ackedQuestionId);
		assertThat(batch).allSatisfy(row -> assertThat(row.answerId()).isPositive());

		List<PendingQuestionCompletion> fullBatch = repository.findPendingBatch(10);
		assertThat(fullBatch).hasSize(3);
		assertThat(fullBatch).extracting(PendingQuestionCompletion::questionId)
			.containsExactlyInAnyOrder(q1, q2, q3);
	}

	@Test
	void returnsEmptyListWhenNothingIsPending() {
		assertThat(repository.findPendingBatch(32)).isEmpty();
	}

	@Test
	void rejectsNonPositiveLimit() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.findPendingBatch(0))
			.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.findPendingBatch(-1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private long insertQuestion() {
		long sequence = SEQUENCE.incrementAndGet();
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "callback-batch-" + sequence + "@example.com")
			.param("nickname", "cb-batch-" + sequence)
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
				'grounded', '[{"sourceId":1}]'::jsonb, :completedAt,
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
}
