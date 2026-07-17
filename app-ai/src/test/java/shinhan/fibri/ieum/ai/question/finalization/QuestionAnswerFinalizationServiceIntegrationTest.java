package shinhan.fibri.ieum.ai.question.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import shinhan.fibri.ieum.ai.question.retrieval.GeoScope;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@SpringJUnitConfig(QuestionAnswerFinalizationServiceIntegrationTest.TestConfiguration.class)
class QuestionAnswerFinalizationServiceIntegrationTest {

	private static final String DATABASE = "ieum_ai_answer_finalization";
	private static final DataSource DATA_SOURCE;

	static {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		DATA_SOURCE = CanonicalPostgresContainer.dataSource(DATABASE);
	}

	private final QuestionAnswerFinalizationService service;
	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	@Autowired
	QuestionAnswerFinalizationServiceIntegrationTest(
		QuestionAnswerFinalizationService service,
		JdbcClient jdbc,
		ObjectMapper objectMapper
	) {
		this.service = service;
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"))
			.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)")
			.update();
	}

	@BeforeEach
	void resetRows() {
		jdbc.sql("DROP TRIGGER IF EXISTS test_fail_task_completion ON ai_question_tasks").update();
		jdbc.sql("DROP FUNCTION IF EXISTS test_fail_task_completion()").update();
		jdbc.sql("DROP TRIGGER IF EXISTS test_hold_answer_insert ON answers").update();
		jdbc.sql("DROP FUNCTION IF EXISTS test_hold_answer_insert()").update();
		jdbc.sql("TRUNCATE ai_question_tasks, answers, questions, pins, users RESTART IDENTITY CASCADE")
			.update();
	}

	@Test
	void atomicallyStoresGroundedAnswerAndCompletedTaskProvenance() throws Exception {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().plusMinutes(2));
		GroundedQuestionAnswerFinalization command = new GroundedQuestionAnswerFinalization(
			fence,
			QuestionAnswerMode.LOCAL_GROUNDED,
			"버스는 앞문으로 타고 뒷문으로 내립니다.",
			context(List.of(evidence("knowledge_chunk")))
		);

		QuestionAnswerFinalizationResult result = service.completeGrounded(command);

		assertThat(result.questionId()).isEqualTo(fence.questionId());
		assertThat(result.hasAnswer()).isTrue();
		assertThat(result.answerId()).isPositive();
		var answer = jdbc.sql("""
			SELECT question_id, author_id, is_ai, content
			FROM answers
			WHERE answer_id = :answerId
			""")
			.param("answerId", result.answerId())
			.query().singleRow();
		assertThat(answer.get("question_id")).isEqualTo(fence.questionId());
		assertThat(answer.get("author_id")).isNull();
		assertThat(answer.get("is_ai")).isEqualTo(true);
		assertThat(answer.get("content")).isEqualTo(command.content());

		var task = jdbc.sql("""
			SELECT status::text,
			       stage::text,
			       answer_id,
			       answer_outcome,
			       generation_provider,
			       generation_model,
			       retrieval_config_version,
			       fallback_reason,
			       prompt_version,
			       grounding_status,
			       grounding_score,
			       geo_scope,
			       geo_scope_confidence,
			       region_context::text,
			       evidence::text,
			       vector_dims(embedding) AS embedding_dimensions,
			       embedding_model,
			       completed_at,
			       lease_until,
			       locked_by,
			       lease_token,
			       answer_notification_processed_at
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", fence.questionId())
			.query().singleRow();
		assertThat(task.get("status")).isEqualTo("completed");
		assertThat(task.get("stage")).isEqualTo("persisting");
		assertThat(task.get("answer_id")).isEqualTo(result.answerId());
		assertThat(task.get("answer_outcome")).isEqualTo("local_grounded");
		assertThat(task.get("generation_provider")).isEqualTo("amazon-bedrock");
		assertThat(task.get("generation_model")).isEqualTo("amazon.nova-micro-v1:0");
		assertThat(task.get("retrieval_config_version")).isEqualTo("hybrid-rag-v1");
		assertThat(task.get("fallback_reason")).isNull();
		assertThat(task.get("prompt_version")).isEqualTo("question-answer-v1");
		assertThat(task.get("grounding_status")).isEqualTo("grounded");
		assertThat(task.get("grounding_score")).isEqualTo(new BigDecimal("0.9100"));
		assertThat(task.get("geo_scope")).isEqualTo("regional");
		assertThat(task.get("geo_scope_confidence")).isEqualTo(new BigDecimal("0.8200"));
		assertThat(objectMapper.readTree((String) task.get("region_context")))
			.isEqualTo(command.context().regionContext());
		assertThat(objectMapper.readTree((String) task.get("evidence")))
			.hasSize(1)
			.first()
			.extracting(node -> node.get("type").textValue())
			.isEqualTo("knowledge_chunk");
		assertThat(task.get("embedding_dimensions")).isEqualTo(768);
		assertThat(task.get("embedding_model")).isEqualTo("gemini-embedding-2");
		assertThat(task.get("completed_at")).isNotNull();
		assertThat(task.get("lease_until")).isNull();
		assertThat(task.get("locked_by")).isNull();
		assertThat(task.get("lease_token")).isNull();
		assertThat(task.get("answer_notification_processed_at")).isNull();
	}

	@Test
	void completesInsufficientEvidenceWithoutCreatingAnAnswer() {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().plusMinutes(2));
		InsufficientQuestionAnswerFinalization command = new InsufficientQuestionAnswerFinalization(
			fence,
			insufficientContext()
		);

		QuestionAnswerFinalizationResult result = service.completeInsufficient(command);

		assertThat(result.questionId()).isEqualTo(fence.questionId());
		assertThat(result.hasAnswer()).isFalse();
		assertThat(result.answerId()).isNull();
		assertThat(answerCount(fence.questionId())).isZero();
		var task = jdbc.sql("""
			SELECT status::text,
			       stage::text,
			       answer_id,
			       answer_outcome,
			       grounding_status,
			       evidence::text,
			       generation_provider,
			       generation_model,
			       prompt_version,
			       completed_at,
			       lease_until,
			       locked_by,
			       lease_token
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", fence.questionId())
			.query().singleRow();
		assertThat(task.get("status")).isEqualTo("completed");
		assertThat(task.get("stage")).isEqualTo("persisting");
		assertThat(task.get("answer_id")).isNull();
		assertThat(task.get("answer_outcome")).isEqualTo("insufficient_evidence");
		assertThat(task.get("grounding_status")).isEqualTo("insufficient_evidence");
		assertThat(task.get("evidence")).isEqualTo("[]");
		assertThat(task.get("generation_provider")).isNull();
		assertThat(task.get("generation_model")).isNull();
		assertThat(task.get("prompt_version")).isNull();
		assertThat(task.get("completed_at")).isNotNull();
		assertThat(task.get("lease_until")).isNull();
		assertThat(task.get("locked_by")).isNull();
		assertThat(task.get("lease_token")).isNull();
	}

	@Test
	void atomicallyStoresUngroundedAnswerAndItsProvenance() {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().plusMinutes(2));
		UngroundedQuestionAnswerFinalization command = new UngroundedQuestionAnswerFinalization(
			fence,
			"현재 검색 근거 없이 생성한 임시 답변입니다.",
			ungroundedContext()
		);

		QuestionAnswerFinalizationResult result = service.completeUngrounded(command);

		assertThat(result.questionId()).isEqualTo(fence.questionId());
		assertThat(result.hasAnswer()).isTrue();
		assertThat(result.answerId()).isPositive();
		assertThat(answerCount(fence.questionId())).isOne();
		var task = jdbc.sql("""
			SELECT status::text,
			       stage::text,
			       answer_id,
			       answer_outcome,
			       generation_provider,
			       generation_model,
			       retrieval_config_version,
			       fallback_reason,
			       prompt_version,
			       grounding_status,
			       grounding_score,
			       evidence::text,
			       completed_at,
			       lease_until,
			       locked_by,
			       lease_token
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", fence.questionId())
			.query().singleRow();
		assertThat(task.get("status")).isEqualTo("completed");
		assertThat(task.get("stage")).isEqualTo("persisting");
		assertThat(task.get("answer_id")).isEqualTo(result.answerId());
		assertThat(task.get("answer_outcome")).isEqualTo("ungrounded");
		assertThat(task.get("generation_provider")).isEqualTo("gemini");
		assertThat(task.get("generation_model")).isEqualTo("gemini-3.1-flash-lite");
		assertThat(task.get("retrieval_config_version")).isEqualTo("hybrid-rag-v1");
		assertThat(task.get("fallback_reason")).isEqualTo("web_grounding_rate_limited");
		assertThat(task.get("prompt_version")).isEqualTo("question-ungrounded-answer-v1");
		assertThat(task.get("grounding_status")).isEqualTo("ungrounded");
		assertThat(task.get("grounding_score")).isNull();
		assertThat(task.get("evidence")).isEqualTo("[]");
		assertThat(task.get("completed_at")).isNotNull();
		assertThat(task.get("lease_until")).isNull();
		assertThat(task.get("locked_by")).isNull();
		assertThat(task.get("lease_token")).isNull();
	}

	@Test
	void rejectsExpiredFenceWithoutWritingAnswerOrTaskResult() {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().minusSeconds(1));

		assertThatThrownBy(() -> service.completeGrounded(grounded(fence)))
			.isInstanceOf(StaleQuestionTaskFinalizationException.class);

		assertThat(answerCount(fence.questionId())).isZero();
		assertThat(taskRow(fence.questionId()).get("status")).isEqualTo("processing");
	}

	@Test
	void rejectsFinalizationBeforeThePersistingStage() {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().plusMinutes(2));
		jdbc.sql("UPDATE ai_question_tasks SET stage = 'validating' WHERE question_id = :questionId")
			.param("questionId", fence.questionId())
			.update();

		assertThatThrownBy(() -> service.completeGrounded(grounded(fence)))
			.isInstanceOf(StaleQuestionTaskFinalizationException.class);

		assertThat(answerCount(fence.questionId())).isZero();
		assertThat(taskRow(fence.questionId()).get("status")).isEqualTo("processing");
		assertThat(taskRow(fence.questionId()).get("stage")).isEqualTo("validating");
	}

	@Test
	void cancelRequestedTaskCannotFinalizeAGroundedAnswer() {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().plusMinutes(2));
		jdbc.sql("UPDATE ai_question_tasks SET cancel_requested_at = CURRENT_TIMESTAMP WHERE question_id = :questionId")
			.param("questionId", fence.questionId())
			.update();

		assertThatThrownBy(() -> service.completeGrounded(grounded(fence)))
			.isInstanceOf(StaleQuestionTaskFinalizationException.class);

		assertThat(answerCount(fence.questionId())).isZero();
		assertThat(taskRow(fence.questionId()))
			.containsEntry("status", "processing")
			.containsEntry("answer_id", null);
	}

	@Test
	void cancelRequestedTaskCannotFinalizeAsInsufficientEvidence() {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().plusMinutes(2));
		jdbc.sql("UPDATE ai_question_tasks SET cancel_requested_at = CURRENT_TIMESTAMP WHERE question_id = :questionId")
			.param("questionId", fence.questionId())
			.update();

		assertThatThrownBy(() -> service.completeInsufficient(new InsufficientQuestionAnswerFinalization(
			fence,
			insufficientContext()
		))).isInstanceOf(StaleQuestionTaskFinalizationException.class);

		assertThat(answerCount(fence.questionId())).isZero();
		assertThat(taskRow(fence.questionId()))
			.containsEntry("status", "processing")
			.containsEntry("answer_id", null)
			.containsEntry("answer_outcome", null)
			.containsEntry("grounding_status", null)
			.containsEntry("completed_at", null);
	}

	@Test
	void staleWorkerCannotFinalizeButCurrentFenceCan() {
		QuestionTaskFence current = insertProcessingTask("worker-b", OffsetDateTime.now().plusMinutes(2));
		QuestionTaskFence stale = new QuestionTaskFence(
			current.questionId(),
			"worker-a",
			UUID.randomUUID()
		);

		assertThatThrownBy(() -> service.completeGrounded(grounded(stale)))
			.isInstanceOf(StaleQuestionTaskFinalizationException.class);
		assertThat(answerCount(current.questionId())).isZero();

		QuestionAnswerFinalizationResult completed = service.completeGrounded(grounded(current));

		assertThat(completed.hasAnswer()).isTrue();
		assertThat(answerCount(current.questionId())).isOne();
		assertThat(taskRow(current.questionId()).get("answer_id")).isEqualTo(completed.answerId());
	}

	@Test
	void deletedQuestionOrPinCancelsTheCurrentFenceWithoutAnAnswer() {
		TaskFixture deletedQuestion = insertProcessingFixture(
			"worker-question",
			OffsetDateTime.now().plusMinutes(2)
		);
		TaskFixture deletedPin = insertProcessingFixture(
			"worker-pin",
			OffsetDateTime.now().plusMinutes(2)
		);
		jdbc.sql("UPDATE questions SET deleted_at = CURRENT_TIMESTAMP WHERE question_id = :questionId")
			.param("questionId", deletedQuestion.fence().questionId())
			.update();
		jdbc.sql("UPDATE pins SET deleted_at = CURRENT_TIMESTAMP WHERE pin_id = :pinId")
			.param("pinId", deletedPin.pinId())
			.update();

		for (TaskFixture fixture : List.of(deletedQuestion, deletedPin)) {
			QuestionAnswerFinalizationResult result = service.completeGrounded(grounded(fixture.fence()));
			assertThat(result.hasAnswer()).isFalse();
			assertThat(answerCount(fixture.fence().questionId())).isZero();
			var task = taskRow(fixture.fence().questionId());
			assertThat(task.get("status")).isEqualTo("cancelled");
			assertThat(task.get("cancelled_at")).isNotNull();
			assertThat(task.get("lease_until")).isNull();
			assertThat(task.get("locked_by")).isNull();
			assertThat(task.get("lease_token")).isNull();
		}
	}

	@Test
	void invalidInputIsRejectedBeforeAnyDatabaseWrite() {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().plusMinutes(2));
		List<Float> invalidEmbedding = embedding().subList(0, 767);

		assertThatThrownBy(() -> new QuestionAnswerFinalizationContext(
			invalidEmbedding,
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			"amazon.nova-micro-v1:0",
			"hybrid-rag-v1",
			null,
			"question-answer-v1",
			BigDecimal.ONE,
			List.of(evidence("knowledge_chunk"))
		)).isInstanceOf(IllegalArgumentException.class);

		assertThat(answerCount(fence.questionId())).isZero();
		assertThat(taskRow(fence.questionId()).get("status")).isEqualTo("processing");
	}

	@Test
	void taskCompletionFailureRollsBackTheInsertedAnswer() {
		QuestionTaskFence fence = insertProcessingTask("worker-a", OffsetDateTime.now().plusMinutes(2));
		jdbc.sql("""
			CREATE FUNCTION test_fail_task_completion()
			RETURNS trigger
			LANGUAGE plpgsql
			AS $$
			BEGIN
			    IF NEW.status = 'completed' THEN
			        RAISE EXCEPTION 'forced task completion failure';
			    END IF;
			    RETURN NEW;
			END;
			$$
			""").update();
		jdbc.sql("""
			CREATE TRIGGER test_fail_task_completion
			BEFORE UPDATE ON ai_question_tasks
			FOR EACH ROW EXECUTE FUNCTION test_fail_task_completion()
			""").update();

		assertThatThrownBy(() -> service.completeGrounded(grounded(fence)))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("forced task completion failure");

		assertThat(answerCount(fence.questionId())).isZero();
		assertThat(taskRow(fence.questionId()).get("status")).isEqualTo("processing");
	}

	@Test
	void ticketFirstLockOrderCompletesConcurrentDeletionWithoutDeadlock() throws Exception {
		TaskFixture fixture = insertProcessingFixture("worker-a", OffsetDateTime.now().plusMinutes(2));
		installAnswerInsertAdvisoryLock();

		try (Connection blocker = DATA_SOURCE.getConnection();
			 ExecutorService executor = Executors.newFixedThreadPool(2)) {
			blocker.setAutoCommit(false);
			try (PreparedStatement statement = blocker.prepareStatement("SELECT pg_advisory_xact_lock(88442211)")) {
				statement.execute();
			}

			Future<QuestionAnswerFinalizationResult> finalization = executor.submit(
				() -> service.completeGrounded(grounded(fixture.fence()))
			);
			awaitAdvisoryWaiter();
			Future<Integer> deletion = executor.submit(() -> softDeleteTicketQuestionAndPin(fixture));

			blocker.commit();

			assertThat(finalization.get(10, TimeUnit.SECONDS).hasAnswer()).isTrue();
			assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(3);
		}

		assertThat(answerCount(fixture.fence().questionId())).isOne();
		assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM questions WHERE question_id = :questionId")
			.param("questionId", fixture.fence().questionId())
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void leaseThatExpiresWhileWaitingForTheTicketLockCannotFinalizeAnAnswer() throws Exception {
		QuestionTaskFence fence = insertProcessingTask(
			"worker-a",
			OffsetDateTime.now().plusSeconds(2)
		);

		try (Connection blocker = DATA_SOURCE.getConnection();
			 ExecutorService executor = Executors.newSingleThreadExecutor()) {
			blocker.setAutoCommit(false);
			executeUpdate(
				blocker,
				"UPDATE ai_question_tasks SET updated_at = updated_at WHERE question_id = ?",
				fence.questionId()
			);

			Future<QuestionAnswerFinalizationResult> finalization = executor.submit(
				() -> service.completeGrounded(grounded(fence))
			);
			awaitTransactionWaiter();
			Thread.sleep(2_200L);
			blocker.commit();

			assertThatThrownBy(() -> finalization.get(10, TimeUnit.SECONDS))
				.hasCauseInstanceOf(StaleQuestionTaskFinalizationException.class);
		}

		assertThat(answerCount(fence.questionId())).isZero();
		assertThat(taskRow(fence.questionId()).get("status")).isEqualTo("processing");
	}

	private GroundedQuestionAnswerFinalization grounded(QuestionTaskFence fence) {
		return new GroundedQuestionAnswerFinalization(
			fence,
			QuestionAnswerMode.LOCAL_GROUNDED,
			"검증된 AI 답변",
			context(List.of(evidence("knowledge_chunk")))
		);
	}

	private int answerCount(long questionId) {
		return jdbc.sql("SELECT count(*) FROM answers WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(Integer.class)
			.single();
	}

	private java.util.Map<String, Object> taskRow(long questionId) {
		return jdbc.sql("""
			SELECT status::text,
			       stage::text,
			       answer_id,
			       answer_outcome,
			       grounding_status,
			       evidence::text,
			       completed_at,
			       cancelled_at,
			       lease_until,
			       locked_by,
			       lease_token
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query().singleRow();
	}

	private void installAnswerInsertAdvisoryLock() {
		jdbc.sql("""
			CREATE FUNCTION test_hold_answer_insert()
			RETURNS trigger
			LANGUAGE plpgsql
			AS $$
			BEGIN
			    PERFORM pg_advisory_xact_lock(88442211);
			    RETURN NEW;
			END;
			$$
			""").update();
		jdbc.sql("""
			CREATE TRIGGER test_hold_answer_insert
			BEFORE INSERT ON answers
			FOR EACH ROW EXECUTE FUNCTION test_hold_answer_insert()
			""").update();
	}

	private void awaitAdvisoryWaiter() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			int waiting = jdbc.sql("""
				SELECT count(*)
				FROM pg_locks
				WHERE locktype = 'advisory'
				  AND granted = false
				""").query(Integer.class).single();
			if (waiting > 0) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("finalization did not reach the answer insert lock");
	}

	private void awaitTransactionWaiter() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			int waiting = jdbc.sql("""
				SELECT count(*)
				FROM pg_locks
				WHERE locktype = 'transactionid'
				  AND granted = false
				""").query(Integer.class).single();
			if (waiting > 0) {
				return;
			}
			Thread.sleep(25L);
		}
		throw new AssertionError("finalization did not wait for the ticket lock");
	}

	private int softDeleteTicketQuestionAndPin(TaskFixture fixture) throws SQLException {
		try (Connection connection = DATA_SOURCE.getConnection()) {
			connection.setAutoCommit(false);
			int updates = executeUpdate(
				connection,
				"UPDATE ai_question_tasks SET cancel_requested_at = CURRENT_TIMESTAMP WHERE question_id = ?",
				fixture.fence().questionId()
			);
			updates += executeUpdate(
				connection,
				"UPDATE questions SET deleted_at = CURRENT_TIMESTAMP WHERE question_id = ?",
				fixture.fence().questionId()
			);
			updates += executeUpdate(
				connection,
				"UPDATE pins SET deleted_at = CURRENT_TIMESTAMP WHERE pin_id = ?",
				fixture.pinId()
			);
			connection.commit();
			return updates;
		}
	}

	private int executeUpdate(Connection connection, String sql, long id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, id);
			return statement.executeUpdate();
		}
	}

	private QuestionAnswerFinalizationContext context(List<JsonNode> evidence) {
		return new QuestionAnswerFinalizationContext(
			embedding(),
			"gemini-embedding-2",
			GeoScope.regional,
			new BigDecimal("0.82"),
			objectMapper.createObjectNode()
				.put("sido", "서울특별시")
				.put("sigungu", "종로구"),
			"amazon-bedrock",
			"amazon.nova-micro-v1:0",
			"hybrid-rag-v1",
			null,
			"question-answer-v1",
			new BigDecimal("0.91"),
			evidence
		);
	}

	private QuestionAnswerFinalizationContext insufficientContext() {
		return new QuestionAnswerFinalizationContext(
			embedding(),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ZERO,
			objectMapper.createObjectNode(),
			null,
			null,
			"hybrid-rag-v1",
			"no_local_evidence",
			null,
			BigDecimal.ZERO,
			List.of()
		);
	}

	private QuestionAnswerFinalizationContext ungroundedContext() {
		return new QuestionAnswerFinalizationContext(
			embedding(),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ZERO,
			objectMapper.createObjectNode(),
			"gemini",
			"gemini-3.1-flash-lite",
			"hybrid-rag-v1",
			"web_grounding_rate_limited",
			"question-ungrounded-answer-v1",
			BigDecimal.ZERO,
			List.of()
		);
	}

	private JsonNode evidence(String type) {
		return objectMapper.createObjectNode()
			.put("type", type)
			.put("sourceId", 10L)
			.put("chunkId", 20L)
			.put("sourceType", "curated")
			.put("title", "한국 버스 이용 안내")
			.put("excerpt", "버스는 앞문으로 승차합니다.")
			.put("contentHash", "a".repeat(64))
			.put("score", 0.91d)
			.put("startIndex", 0)
			.put("endIndex", 3)
			.put("retrievedAt", "2026-07-13T00:00:00Z");
	}

	private List<Float> embedding() {
		List<Float> values = new ArrayList<>(768);
		for (int index = 0; index < 768; index++) {
			values.add(index == 0 ? 1.0f : 0.0f);
		}
		return values;
	}

	private QuestionTaskFence insertProcessingTask(String workerId, OffsetDateTime leaseUntil) {
		return insertProcessingFixture(workerId, leaseUntil).fence();
	}

	private TaskFixture insertProcessingFixture(String workerId, OffsetDateTime leaseUntil) {
		long userId = jdbc.sql("""
			INSERT INTO users(email, nickname)
			VALUES (:email, :nickname)
			RETURNING user_id
			""")
			.param("email", UUID.randomUUID() + "@example.com")
			.param("nickname", "user-" + UUID.randomUUID())
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
			VALUES (:pinId, :userId, '버스 이용법', '한국에서 버스는 어떻게 타나요?')
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.query(Long.class)
			.single();
		UUID leaseToken = UUID.randomUUID();
		jdbc.sql("""
			INSERT INTO ai_question_tasks(
			    question_id, status, stage, attempts, lease_until, locked_by, lease_token, started_at
			)
			VALUES (
			    :questionId, 'processing', 'persisting', 1, :leaseUntil, :workerId, :leaseToken, CURRENT_TIMESTAMP
			)
			""")
			.param("questionId", questionId)
			.param("leaseUntil", leaseUntil)
			.param("workerId", workerId)
			.param("leaseToken", leaseToken)
			.update();
		return new TaskFixture(new QuestionTaskFence(questionId, workerId, leaseToken), pinId);
	}

	private record TaskFixture(QuestionTaskFence fence, long pinId) {
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class TestConfiguration {

		@Bean
		DataSource dataSource() {
			return DATA_SOURCE;
		}

		@Bean
		JdbcClient jdbcClient(DataSource dataSource) {
			return JdbcClient.create(dataSource);
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		JdbcQuestionAnswerFinalizationRepository finalizationRepository(
			JdbcClient jdbcClient,
			ObjectMapper objectMapper
		) {
			return new JdbcQuestionAnswerFinalizationRepository(jdbcClient, objectMapper);
		}

		@Bean
		QuestionAnswerFinalizationService finalizationService(
			JdbcQuestionAnswerFinalizationRepository repository
		) {
			return new QuestionAnswerFinalizationService(repository);
		}
	}
}
