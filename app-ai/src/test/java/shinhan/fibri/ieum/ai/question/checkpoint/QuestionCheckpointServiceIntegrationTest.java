package shinhan.fibri.ieum.ai.question.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.QueryAnalysis;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbedding;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@SpringJUnitConfig(QuestionCheckpointServiceIntegrationTest.TestConfiguration.class)
class QuestionCheckpointServiceIntegrationTest {

	private static final String DATABASE = "ieum_ai_question_checkpoint";
	private static final long ADVISORY_LOCK_ID = 77331144L;
	private static final DataSource DATA_SOURCE;

	static {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		DATA_SOURCE = CanonicalPostgresContainer.dataSource(DATABASE);
	}

	private final QuestionCheckpointService service;
	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	@Autowired
	QuestionCheckpointServiceIntegrationTest(
		QuestionCheckpointService service,
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
		jdbc.sql("DROP TRIGGER IF EXISTS test_hold_checkpoint_update ON ai_question_tasks").update();
		jdbc.sql("DROP FUNCTION IF EXISTS test_hold_checkpoint_update()").update();
		jdbc.sql("DROP TRIGGER IF EXISTS test_hold_guard_update ON ai_question_tasks").update();
		jdbc.sql("DROP FUNCTION IF EXISTS test_hold_guard_update()").update();
		jdbc.sql("TRUNCATE ai_question_tasks, answers, questions, pins, users RESTART IDENTITY CASCADE")
			.update();
	}

	@Test
	void savesAnalysisAsFiveKeyJsonAndRenewsLeaseBeforeEmbedding() throws Exception {
		TaskFixture fixture = insertProcessingTask("analyzing", false, false, false, activeLease());
		OffsetDateTime before = OffsetDateTime.now();

		QuestionCheckpointResult result = service.saveAnalysis(
			fixture.claim(),
			analysis(),
			Duration.ofMinutes(2)
		);

		assertThat(result).isEqualTo(QuestionCheckpointResult.APPLIED);
		var row = taskRow(fixture.claim().questionId());
		assertThat(row.get("stage")).isEqualTo("embedding");
		assertThat(row.get("geo_scope")).isEqualTo("regional");
		assertThat(row.get("geo_scope_confidence")).isEqualTo(new BigDecimal("0.8200"));
		assertThat(row.get("analysis_version")).isEqualTo("query-analysis-v1");
		JsonNode region = objectMapper.readTree((String) row.get("region_context"));
		assertThat(region.size()).isEqualTo(5);
		assertThat(region.get("country").textValue()).isEqualTo("KR");
		assertThat(region.get("sido").textValue()).isEqualTo("서울특별시");
		assertThat(region.get("sigungu").textValue()).isEqualTo("종로구");
		assertThat(region.get("eupMyeonDong").isNull()).isTrue();
		assertThat(region.get("place").isNull()).isTrue();
		assertThat(((Timestamp) row.get("lease_until")).toInstant())
			.isAfter(before.plusSeconds(115).toInstant());
	}

	@Test
	void savesGeminiEmbeddingAndAdvancesToRetrieving() {
		TaskFixture fixture = insertProcessingTask("embedding", false, false, false, activeLease());

		QuestionCheckpointResult result = service.saveEmbedding(
			fixture.claim(),
			new QuestionEmbedding("gemini-embedding-2", embedding()),
			Duration.ofMinutes(2)
		);

		assertThat(result).isEqualTo(QuestionCheckpointResult.APPLIED);
		var row = jdbc.sql("""
			SELECT stage::text,
			       vector_dims(embedding) AS dimensions,
			       embedding_model,
			       lease_until
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", fixture.claim().questionId())
			.query().singleRow();
		assertThat(row.get("stage")).isEqualTo("retrieving");
		assertThat(row.get("dimensions")).isEqualTo(768);
		assertThat(row.get("embedding_model")).isEqualTo("gemini-embedding-2");
		assertThat(((Timestamp) row.get("lease_until")).toInstant())
			.isAfter(OffsetDateTime.now().plusSeconds(110).toInstant());
	}

	@Test
	void guardCurrentStageRenewsOnlyTheLeaseWithoutAdvancingTheStage() {
		TaskFixture fixture = insertProcessingTask(
			"generating",
			false,
			false,
			false,
			OffsetDateTime.now().plusSeconds(30)
		);
		OffsetDateTime before = OffsetDateTime.now();

		QuestionCheckpointResult result = service.guardCurrentStage(
			fixture.claim(),
			QuestionTaskStage.GENERATING,
			Duration.ofMinutes(3)
		);

		assertThat(result).isEqualTo(QuestionCheckpointResult.APPLIED);
		var row = taskRow(fixture.claim().questionId());
		assertThat(row.get("stage")).isEqualTo("generating");
		assertThat(row.get("analysis_version")).isNull();
		assertThat(((Timestamp) row.get("lease_until")).toInstant())
			.isAfter(before.plusSeconds(175).toInstant());
	}

	@Test
	void guardCurrentStageNeverShortensAnExistingLongerLease() {
		TaskFixture fixture = insertProcessingTask(
			"generating",
			false,
			false,
			false,
			OffsetDateTime.now().plusMinutes(10)
		);
		var leaseBefore = ((Timestamp) taskRow(fixture.claim().questionId()).get("lease_until")).toInstant();

		QuestionCheckpointResult result = service.guardCurrentStage(
			fixture.claim(),
			QuestionTaskStage.GENERATING,
			Duration.ofMinutes(2)
		);

		assertThat(result).isEqualTo(QuestionCheckpointResult.APPLIED);
		var leaseAfter = ((Timestamp) taskRow(fixture.claim().questionId()).get("lease_until")).toInstant();
		assertThat(leaseAfter).isEqualTo(leaseBefore);
	}

	@Test
	void guardCurrentStageCancelsForRequestDeletedQuestionOrDeletedPin() {
		TaskFixture requested = insertProcessingTask("generating", true, false, false, activeLease());
		TaskFixture deletedQuestion = insertProcessingTask("generating", false, true, false, activeLease());
		TaskFixture deletedPin = insertProcessingTask("generating", false, false, true, activeLease());

		for (TaskFixture fixture : List.of(requested, deletedQuestion, deletedPin)) {
			QuestionCheckpointResult result = service.guardCurrentStage(
				fixture.claim(),
				QuestionTaskStage.GENERATING,
				Duration.ofMinutes(2)
			);

			assertThat(result).isEqualTo(QuestionCheckpointResult.CANCELLED);
			var row = taskRow(fixture.claim().questionId());
			assertThat(row.get("status")).isEqualTo("cancelled");
			assertThat(row.get("lease_until")).isNull();
			assertThat(row.get("locked_by")).isNull();
			assertThat(row.get("lease_token")).isNull();
		}
	}

	@Test
	void guardCurrentStageRejectsWrongWorkerTokenStageOrExpiredLease() {
		TaskFixture base = insertProcessingTask("generating", false, false, false, activeLease());
		ClaimedQuestionTask wrongWorker = new ClaimedQuestionTask(
			base.claim().questionId(),
			"other-worker",
			base.claim().leaseToken(),
			base.claim().leaseUntil(),
			base.claim().attempts()
		);
		ClaimedQuestionTask wrongToken = new ClaimedQuestionTask(
			base.claim().questionId(),
			base.claim().workerId(),
			UUID.randomUUID(),
			base.claim().leaseUntil(),
			base.claim().attempts()
		);
		TaskFixture wrongStage = insertProcessingTask("validating", false, false, false, activeLease());
		TaskFixture expired = insertProcessingTask(
			"generating",
			false,
			false,
			false,
			OffsetDateTime.now().minusSeconds(1)
		);

		for (ClaimedQuestionTask stale : List.of(wrongWorker, wrongToken, expired.claim())) {
			assertThatThrownBy(() -> service.guardCurrentStage(
				stale,
				QuestionTaskStage.GENERATING,
				Duration.ofMinutes(2)
			)).isInstanceOf(StaleQuestionCheckpointException.class);
		}
		assertThatThrownBy(() -> service.guardCurrentStage(
			wrongStage.claim(),
			QuestionTaskStage.GENERATING,
			Duration.ofMinutes(2)
		)).isInstanceOf(StaleQuestionCheckpointException.class);
		assertThat(stage(wrongStage.claim().questionId())).isEqualTo("validating");
	}

	@Test
	void advancesOnlyTheExplicitRuntimeStagePaths() {
		List<Transition> transitions = List.of(
			new Transition(QuestionTaskStage.RETRIEVING, QuestionTaskStage.GENERATING),
			new Transition(QuestionTaskStage.RETRIEVING, QuestionTaskStage.WEB_GROUNDING),
			new Transition(QuestionTaskStage.RETRIEVING, QuestionTaskStage.PERSISTING),
			new Transition(QuestionTaskStage.GENERATING, QuestionTaskStage.VALIDATING),
			new Transition(QuestionTaskStage.VALIDATING, QuestionTaskStage.WEB_GROUNDING),
			new Transition(QuestionTaskStage.VALIDATING, QuestionTaskStage.PERSISTING),
			new Transition(QuestionTaskStage.WEB_GROUNDING, QuestionTaskStage.PERSISTING)
		);

		for (Transition transition : transitions) {
			TaskFixture fixture = insertProcessingTask(
				transition.expected().databaseValue(),
				false,
				false,
				false,
				activeLease()
			);

			QuestionCheckpointResult result = service.guardAndAdvance(
				fixture.claim(),
				transition.expected(),
				transition.next(),
				Duration.ofMinutes(2)
			);

			assertThat(result).isEqualTo(QuestionCheckpointResult.APPLIED);
			assertThat(stage(fixture.claim().questionId()))
				.isEqualTo(transition.next().databaseValue());
		}
	}

	@Test
	void cancellationRequestOrInactiveQuestionCancelsAndClearsTheFence() {
		TaskFixture requested = insertProcessingTask("analyzing", true, false, false, activeLease());
		TaskFixture deletedQuestion = insertProcessingTask("analyzing", false, true, false, activeLease());
		TaskFixture deletedPin = insertProcessingTask("analyzing", false, false, true, activeLease());

		for (TaskFixture fixture : List.of(requested, deletedQuestion, deletedPin)) {
			QuestionCheckpointResult result = service.saveAnalysis(
				fixture.claim(),
				analysis(),
				Duration.ofMinutes(2)
			);
			assertThat(result).isEqualTo(QuestionCheckpointResult.CANCELLED);
			var row = taskRow(fixture.claim().questionId());
			assertThat(row.get("status")).isEqualTo("cancelled");
			assertThat(row.get("cancelled_at")).isNotNull();
			assertThat(row.get("lease_until")).isNull();
			assertThat(row.get("locked_by")).isNull();
			assertThat(row.get("lease_token")).isNull();
			assertThat(row.get("analysis_version")).isNull();
		}
	}

	@Test
	void wrongWorkerExpiredLeaseOrWrongStageIsStaleWithoutCheckpointWrite() {
		TaskFixture wrongWorkerFixture = insertProcessingTask("analyzing", false, false, false, activeLease());
		ClaimedQuestionTask wrongWorker = new ClaimedQuestionTask(
			wrongWorkerFixture.claim().questionId(),
			"other-worker",
			wrongWorkerFixture.claim().leaseToken(),
			wrongWorkerFixture.claim().leaseUntil(),
			wrongWorkerFixture.claim().attempts()
		);
		TaskFixture expired = insertProcessingTask(
			"analyzing",
			false,
			false,
			false,
			OffsetDateTime.now().minusSeconds(1)
		);
		TaskFixture wrongStage = insertProcessingTask("embedding", false, false, false, activeLease());

		for (ClaimedQuestionTask stale : List.of(wrongWorker, expired.claim())) {
			assertThatThrownBy(() -> service.saveAnalysis(stale, analysis(), Duration.ofMinutes(2)))
				.isInstanceOf(StaleQuestionCheckpointException.class);
			assertThat(taskRow(stale.questionId()).get("analysis_version")).isNull();
		}
		assertThatThrownBy(() -> service.saveAnalysis(
			wrongStage.claim(),
			analysis(),
			Duration.ofMinutes(2)
		)).isInstanceOf(StaleQuestionCheckpointException.class);
		assertThat(stage(wrongStage.claim().questionId())).isEqualTo("embedding");
	}

	@Test
	void invalidEmbeddingIsRejectedBeforeTicketMutation() {
		TaskFixture fixture = insertProcessingTask("embedding", false, false, false, activeLease());

		assertThatThrownBy(() -> service.saveEmbedding(
			fixture.claim(),
			new QuestionEmbedding("gemini-embedding-001", embedding()),
			Duration.ofMinutes(2)
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.saveEmbedding(
			fixture.claim(),
			new QuestionEmbedding("gemini-embedding-2", embedding().subList(0, 767)),
			Duration.ofMinutes(2)
		)).isInstanceOf(IllegalArgumentException.class);

		assertThat(stage(fixture.claim().questionId())).isEqualTo("embedding");
	}

	@Test
	void ticketFirstCheckpointLockCompletesConcurrentDeletionWithoutDeadlock() throws Exception {
		TaskFixture fixture = insertProcessingTask("analyzing", false, false, false, activeLease());
		installCheckpointAdvisoryLock();

		try (Connection blocker = DATA_SOURCE.getConnection();
			 ExecutorService executor = Executors.newFixedThreadPool(2)) {
			blocker.setAutoCommit(false);
			try (PreparedStatement statement = blocker.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
				statement.setLong(1, ADVISORY_LOCK_ID);
				statement.execute();
			}

			Future<QuestionCheckpointResult> checkpoint = executor.submit(() -> service.saveAnalysis(
				fixture.claim(),
				analysis(),
				Duration.ofMinutes(2)
			));
			awaitLockWaiter("advisory");
			Future<Integer> deletion = executor.submit(() -> softDeleteTicketQuestionAndPin(fixture));
			awaitLockWaiter("transactionid");

			blocker.commit();

			assertThat(checkpoint.get(10, TimeUnit.SECONDS)).isEqualTo(QuestionCheckpointResult.APPLIED);
			assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(3);
		}

		assertThat(stage(fixture.claim().questionId())).isEqualTo("embedding");
		assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM questions WHERE question_id = :questionId")
			.param("questionId", fixture.claim().questionId())
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void ticketFirstGuardLockCompletesConcurrentDeletionWithoutDeadlock() throws Exception {
		TaskFixture fixture = insertProcessingTask("generating", false, false, false, activeLease());
		installGuardAdvisoryLock();

		try (Connection blocker = DATA_SOURCE.getConnection();
			 ExecutorService executor = Executors.newFixedThreadPool(2)) {
			blocker.setAutoCommit(false);
			try (PreparedStatement statement = blocker.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
				statement.setLong(1, ADVISORY_LOCK_ID);
				statement.execute();
			}

			Future<QuestionCheckpointResult> guard = executor.submit(() -> service.guardCurrentStage(
				fixture.claim(),
				QuestionTaskStage.GENERATING,
				Duration.ofMinutes(2)
			));
			awaitLockWaiter("advisory");
			Future<Integer> deletion = executor.submit(() -> softDeleteTicketQuestionAndPin(fixture));
			awaitLockWaiter("transactionid");

			blocker.commit();

			assertThat(guard.get(10, TimeUnit.SECONDS)).isEqualTo(QuestionCheckpointResult.APPLIED);
			assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(3);
		}

		assertThat(stage(fixture.claim().questionId())).isEqualTo("generating");
		assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM questions WHERE question_id = :questionId")
			.param("questionId", fixture.claim().questionId())
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void leaseThatExpiresWhileWaitingForTheTicketLockIsStale() throws Exception {
		TaskFixture fixture = insertProcessingTask(
			"analyzing",
			false,
			false,
			false,
			OffsetDateTime.now().plusSeconds(2)
		);

		try (Connection blocker = DATA_SOURCE.getConnection();
			 ExecutorService executor = Executors.newSingleThreadExecutor()) {
			blocker.setAutoCommit(false);
			executeUpdate(
				blocker,
				"UPDATE ai_question_tasks SET updated_at = updated_at WHERE question_id = ?",
				fixture.claim().questionId()
			);

			Future<QuestionCheckpointResult> checkpoint = executor.submit(() -> service.saveAnalysis(
				fixture.claim(),
				analysis(),
				Duration.ofMinutes(2)
			));
			awaitLockWaiter("transactionid");
			Thread.sleep(2_200L);
			blocker.commit();

			assertThatThrownBy(() -> checkpoint.get(10, TimeUnit.SECONDS))
				.hasCauseInstanceOf(StaleQuestionCheckpointException.class);
		}

		assertThat(stage(fixture.claim().questionId())).isEqualTo("analyzing");
		assertThat(taskRow(fixture.claim().questionId()).get("analysis_version")).isNull();
	}

	@Test
	void guardLeaseThatExpiresWhileWaitingForTheTicketLockIsStale() throws Exception {
		TaskFixture fixture = insertProcessingTask(
			"generating",
			false,
			false,
			false,
			OffsetDateTime.now().plusSeconds(2)
		);

		try (Connection blocker = DATA_SOURCE.getConnection();
			 ExecutorService executor = Executors.newSingleThreadExecutor()) {
			blocker.setAutoCommit(false);
			executeUpdate(
				blocker,
				"UPDATE ai_question_tasks SET updated_at = updated_at WHERE question_id = ?",
				fixture.claim().questionId()
			);

			Future<QuestionCheckpointResult> guard = executor.submit(() -> service.guardCurrentStage(
				fixture.claim(),
				QuestionTaskStage.GENERATING,
				Duration.ofMinutes(2)
			));
			awaitLockWaiter("transactionid");
			Thread.sleep(2_200L);
			blocker.commit();

			assertThatThrownBy(() -> guard.get(10, TimeUnit.SECONDS))
				.hasCauseInstanceOf(StaleQuestionCheckpointException.class);
		}

		assertThat(stage(fixture.claim().questionId())).isEqualTo("generating");
	}

	@Test
	void deletionThatOwnsTicketQuestionAndPinFirstMakesCheckpointCancelWithoutAWrite() throws Exception {
		TaskFixture fixture = insertProcessingTask("analyzing", false, false, false, activeLease());

		try (Connection deletion = DATA_SOURCE.getConnection();
			 ExecutorService executor = Executors.newSingleThreadExecutor()) {
			deletion.setAutoCommit(false);
			executeUpdate(
				deletion,
				"UPDATE ai_question_tasks SET cancel_requested_at = CURRENT_TIMESTAMP WHERE question_id = ?",
				fixture.claim().questionId()
			);
			executeUpdate(
				deletion,
				"UPDATE questions SET deleted_at = CURRENT_TIMESTAMP WHERE question_id = ?",
				fixture.claim().questionId()
			);
			executeUpdate(
				deletion,
				"UPDATE pins SET deleted_at = CURRENT_TIMESTAMP WHERE pin_id = ?",
				fixture.pinId()
			);

			Future<QuestionCheckpointResult> checkpoint = executor.submit(() -> service.saveAnalysis(
				fixture.claim(),
				analysis(),
				Duration.ofMinutes(2)
			));
			awaitLockWaiter("transactionid");
			deletion.commit();

			assertThat(checkpoint.get(10, TimeUnit.SECONDS)).isEqualTo(QuestionCheckpointResult.CANCELLED);
		}

		var task = taskRow(fixture.claim().questionId());
		assertThat(task.get("status")).isEqualTo("cancelled");
		assertThat(task.get("analysis_version")).isNull();
	}

	private QueryAnalysis analysis() {
		return new QueryAnalysis(
			GeoScope.regional,
			new BigDecimal("0.82"),
			RegionContext.korea("서울특별시", "종로구", null, null),
			"transport",
			false,
			List.of("버스", "앞문"),
			List.of("한국 버스 승하차"),
			"query-analysis-v1"
		);
	}

	private List<Float> embedding() {
		List<Float> values = new ArrayList<>(768);
		for (int index = 0; index < 768; index++) {
			values.add(index == 0 ? 1.0f : 0.0f);
		}
		return values;
	}

	private OffsetDateTime activeLease() {
		return OffsetDateTime.now().plusMinutes(2);
	}

	private String stage(long questionId) {
		return jdbc.sql("SELECT stage::text FROM ai_question_tasks WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(String.class)
			.single();
	}

	private java.util.Map<String, Object> taskRow(long questionId) {
		return jdbc.sql("""
			SELECT status::text,
			       stage::text,
			       geo_scope,
			       geo_scope_confidence,
			       region_context::text,
			       analysis_version,
			       lease_until,
			       locked_by,
			       lease_token,
			       cancelled_at
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query().singleRow();
	}

	private TaskFixture insertProcessingTask(
		String stage,
		boolean cancelRequested,
		boolean deletedQuestion,
		boolean deletedPin,
		OffsetDateTime leaseUntil
	) {
		long userId = jdbc.sql("""
			INSERT INTO users(email, nickname)
			VALUES (:email, :nickname)
			RETURNING user_id
			""")
			.param("email", UUID.randomUUID() + "@example.com")
			.param("nickname", "checkpoint-" + UUID.randomUUID())
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins(author_id, pin_type, location, address, deleted_at)
			VALUES (
			    :userId,
			    'question',
			    ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326),
			    '서울특별시 종로구',
			    CASE WHEN :deletedPin THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING pin_id
			""")
			.param("userId", userId)
			.param("deletedPin", deletedPin)
			.query(Long.class)
			.single();
		long questionId = jdbc.sql("""
			INSERT INTO questions(pin_id, author_id, title, content, deleted_at)
			VALUES (
			    :pinId,
			    :userId,
			    '버스 이용법',
			    '한국에서 버스는 어떻게 타나요?',
			    CASE WHEN :deletedQuestion THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.param("deletedQuestion", deletedQuestion)
			.query(Long.class)
			.single();
		String workerId = "worker-" + questionId;
		UUID token = UUID.randomUUID();
		jdbc.sql("""
			INSERT INTO ai_question_tasks(
			    question_id,
			    status,
			    stage,
			    attempts,
			    lease_until,
			    locked_by,
			    lease_token,
			    started_at,
			    cancel_requested_at
			)
			VALUES (
			    :questionId,
			    'processing',
			    CAST(:stage AS ai_job_stage),
			    1,
			    :leaseUntil,
			    :workerId,
			    :leaseToken,
			    CURRENT_TIMESTAMP,
			    CASE WHEN :cancelRequested THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			""")
			.param("questionId", questionId)
			.param("stage", stage)
			.param("leaseUntil", leaseUntil)
			.param("workerId", workerId)
			.param("leaseToken", token)
			.param("cancelRequested", cancelRequested)
			.update();
		return new TaskFixture(
			new ClaimedQuestionTask(questionId, workerId, token, leaseUntil, 1),
			pinId
		);
	}

	private void installCheckpointAdvisoryLock() {
		jdbc.sql("""
			CREATE FUNCTION test_hold_checkpoint_update()
			RETURNS trigger
			LANGUAGE plpgsql
			AS $$
			BEGIN
			    IF NEW.stage = 'embedding' THEN
			        PERFORM pg_advisory_xact_lock(77331144);
			    END IF;
			    RETURN NEW;
			END;
			$$
			""").update();
		jdbc.sql("""
			CREATE TRIGGER test_hold_checkpoint_update
			BEFORE UPDATE ON ai_question_tasks
			FOR EACH ROW EXECUTE FUNCTION test_hold_checkpoint_update()
			""").update();
	}

	private void installGuardAdvisoryLock() {
		jdbc.sql("""
			CREATE FUNCTION test_hold_guard_update()
			RETURNS trigger
			LANGUAGE plpgsql
			AS $$
			BEGIN
			    IF NEW.status = 'processing'
			       AND NEW.stage = OLD.stage
			       AND NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN
			        PERFORM pg_advisory_xact_lock(77331144);
			    END IF;
			    RETURN NEW;
			END;
			$$
			""").update();
		jdbc.sql("""
			CREATE TRIGGER test_hold_guard_update
			BEFORE UPDATE ON ai_question_tasks
			FOR EACH ROW EXECUTE FUNCTION test_hold_guard_update()
			""").update();
	}

	private void awaitLockWaiter(String lockType) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			int waiting = jdbc.sql("""
				SELECT count(*)
				FROM pg_locks
				WHERE locktype = :lockType
				  AND granted = false
				""")
				.param("lockType", lockType)
				.query(Integer.class)
				.single();
			if (waiting > 0) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("expected waiting " + lockType + " lock");
	}

	private int softDeleteTicketQuestionAndPin(TaskFixture fixture) throws SQLException {
		try (Connection connection = DATA_SOURCE.getConnection()) {
			connection.setAutoCommit(false);
			int updates = executeUpdate(
				connection,
				"UPDATE ai_question_tasks SET cancel_requested_at = CURRENT_TIMESTAMP WHERE question_id = ?",
				fixture.claim().questionId()
			);
			updates += executeUpdate(
				connection,
				"UPDATE questions SET deleted_at = CURRENT_TIMESTAMP WHERE question_id = ?",
				fixture.claim().questionId()
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

	private record TaskFixture(ClaimedQuestionTask claim, long pinId) {
	}

	private record Transition(QuestionTaskStage expected, QuestionTaskStage next) {
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
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		JdbcQuestionCheckpointRepository checkpointRepository(
			JdbcClient jdbcClient,
			ObjectMapper objectMapper
		) {
			return new JdbcQuestionCheckpointRepository(jdbcClient, objectMapper);
		}

		@Bean
		QuestionCheckpointService checkpointService(QuestionCheckpointRepository repository) {
			return new QuestionCheckpointService(repository);
		}
	}
}
