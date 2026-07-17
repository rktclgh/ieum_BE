package shinhan.fibri.ieum.main.ai.question.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcQuestionAnswerTicketWriterIntegrationTest {

	private static final String DATABASE = "ieum_main_answer_ticket";

	private JdbcClient jdbc;
	private JdbcQuestionAnswerTicketWriter writer;

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
		writer = new JdbcQuestionAnswerTicketWriter(jdbc);
	}

	@Test
	void createsOnePendingTicketEvenWhenCalledMoreThanOnce() {
		long questionId = insertQuestion();

		writer.create(questionId);
		writer.create(questionId);

		assertThat(jdbc.sql("""
			SELECT status::text
			  FROM ai_question_tasks
			 WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query(String.class)
			.single()).isEqualTo("pending");
		assertThat(jdbc.sql("""
			SELECT count(*)
			  FROM ai_question_tasks
			 WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void doesNotCreateAnOrphanTicket() {
		assertThatThrownBy(() -> writer.create(Long.MAX_VALUE))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void requestsCancellationOnlyForRunnableStatuses() {
		long pendingQuestionId = insertQuestion();
		long retryQuestionId = insertQuestion();
		long processingQuestionId = insertQuestion();
		writer.create(pendingQuestionId);
		writer.create(retryQuestionId);
		writer.create(processingQuestionId);
		jdbc.sql("UPDATE ai_question_tasks SET status = 'retry' WHERE question_id = :questionId")
			.param("questionId", retryQuestionId)
			.update();
		jdbc.sql("""
			UPDATE ai_question_tasks
			   SET status = 'processing',
			       lease_until = now() + interval '5 minutes',
			       locked_by = 'test-worker',
			       lease_token = gen_random_uuid()
			 WHERE question_id = :questionId
			""")
			.param("questionId", processingQuestionId)
			.update();

		writer.requestCancellation(pendingQuestionId);
		writer.requestCancellation(retryQuestionId);
		writer.requestCancellation(processingQuestionId);

		assertThat(cancellationRequestedAt(pendingQuestionId)).isNotNull();
		assertThat(cancellationRequestedAt(retryQuestionId)).isNotNull();
		assertThat(cancellationRequestedAt(processingQuestionId)).isNotNull();
	}

	@Test
	void doesNotRequestCancellationForTerminalStatus() {
		long terminalQuestionId = insertQuestion();
		writer.create(terminalQuestionId);
		jdbc.sql("UPDATE ai_question_tasks SET status = 'dead' WHERE question_id = :questionId")
			.param("questionId", terminalQuestionId)
			.update();

		writer.requestCancellation(terminalQuestionId);

		assertThat(cancellationRequestedAt(terminalQuestionId)).isNull();
	}

	@Test
	void preservesFirstCancellationRequestTimeAcrossRetries() {
		long questionId = insertQuestion();
		writer.create(questionId);

		writer.requestCancellation(questionId);
		OffsetDateTime firstRequestedAt = cancellationRequestedAt(questionId);
		writer.requestCancellation(questionId);

		assertThat(cancellationRequestedAt(questionId)).isEqualTo(firstRequestedAt);
	}

	@Test
	void reArmsRunningTicketBackToPendingAndClearsOutputsAndLease() {
		long questionId = insertQuestion();
		writer.create(questionId);
		jdbc.sql("""
			UPDATE ai_question_tasks
			   SET status = 'processing',
			       stage = 'answering',
			       attempts = 3,
			       lease_until = now() + interval '5 minutes',
			       locked_by = 'test-worker',
			       lease_token = gen_random_uuid(),
			       cancel_requested_at = now(),
			       last_error_code = 'PREVIOUS_ERROR'
			 WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.update();

		boolean reArmed = writer.requestRegeneration(questionId);

		assertThat(reArmed).isTrue();
		assertThat(status(questionId)).isEqualTo("pending");
		assertThat(text(questionId, "stage")).isEqualTo("discovered");
		assertThat(number(questionId, "attempts")).isZero();
		assertThat(rawIsNull(questionId, "lease_token")).isTrue();
		assertThat(rawIsNull(questionId, "locked_by")).isTrue();
		assertThat(rawIsNull(questionId, "lease_until")).isTrue();
		assertThat(rawIsNull(questionId, "cancel_requested_at")).isTrue();
		assertThat(rawIsNull(questionId, "last_error_code")).isTrue();
		assertThat(number(questionId, "jsonb_array_length(evidence)")).isZero();
	}

	@Test
	void reArmsPendingRetryAndCancelledAndDeadTickets() {
		for (String startingStatus : new String[] {"pending", "retry", "cancelled", "dead"}) {
			long questionId = insertQuestion();
			writer.create(questionId);
			if (startingStatus.equals("cancelled")) {
				jdbc.sql("UPDATE ai_question_tasks SET status = 'cancelled', cancelled_at = now() WHERE question_id = :questionId")
					.param("questionId", questionId)
					.update();
			}
			else if (!startingStatus.equals("pending")) {
				jdbc.sql("UPDATE ai_question_tasks SET status = CAST(:status AS ai_job_status) WHERE question_id = :questionId")
					.param("status", startingStatus)
					.param("questionId", questionId)
					.update();
			}

			boolean reArmed = writer.requestRegeneration(questionId);

			assertThat(reArmed).as("status %s should re-arm", startingStatus).isTrue();
			assertThat(status(questionId)).isEqualTo("pending");
			assertThat(rawIsNull(questionId, "cancelled_at")).isTrue();
		}
	}

	@Test
	void doesNotReArmCompletedTicket() {
		long questionId = insertQuestion();
		writer.create(questionId);
		markCompleted(questionId);

		boolean reArmed = writer.requestRegeneration(questionId);

		assertThat(reArmed).isFalse();
		assertThat(status(questionId)).isEqualTo("completed");
	}

	private void markCompleted(long questionId) {
		jdbc.sql("""
			UPDATE ai_question_tasks
			   SET status = 'completed',
			       completed_at = now(),
			       embedding = ('[' || array_to_string(array_fill(0::real, ARRAY[768]), ',') || ']')::vector,
			       embedding_model = 'gemini-embedding-2',
			       answer_outcome = 'insufficient_evidence',
			       grounding_status = 'insufficient_evidence'
			 WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.update();
	}

	private String status(long questionId) {
		return text(questionId, "status::text");
	}

	private String text(long questionId, String expression) {
		return jdbc.sql("SELECT " + expression + " FROM ai_question_tasks WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(String.class)
			.single();
	}

	private int number(long questionId, String expression) {
		return jdbc.sql("SELECT " + expression + " FROM ai_question_tasks WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(Integer.class)
			.single();
	}

	private boolean rawIsNull(long questionId, String column) {
		return jdbc.sql("SELECT " + column + " IS NULL FROM ai_question_tasks WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(Boolean.class)
			.single();
	}

	private OffsetDateTime cancellationRequestedAt(long questionId) {
		return jdbc.sql("""
			SELECT cancel_requested_at
			  FROM ai_question_tasks
			 WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query(OffsetDateTime.class)
			.optional()
			.orElse(null);
	}

	private long insertQuestion() {
		String suffix = UUID.randomUUID().toString();
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "answer-ticket-" + suffix + "@example.com")
			.param("nickname", "ticket-" + suffix.substring(0, 8))
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (
				:userId,
				'question',
				ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography,
				'Seoul'
			)
			RETURNING pin_id
			""")
			.param("userId", userId)
			.query(Long.class)
			.single();
		return jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, :userId, 'question title', 'question content')
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.query(Long.class)
			.single();
	}
}
