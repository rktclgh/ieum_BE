package shinhan.fibri.ieum.ai.knowledge.accepted;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import shinhan.fibri.ieum.ai.question.analysis.StoredAddressRegionParser;
import shinhan.fibri.ieum.ai.question.webgrounding.WebQuestionPiiSanitizer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcAcceptedAnswerKnowledgeRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_ai_accepted_knowledge";
	private static final Duration LEASE = Duration.ofMinutes(5);
	private static final Duration RETRY_DELAY = Duration.ofMinutes(2);
	private static final int MAX_ATTEMPTS = 5;

	private DataSource dataSource;
	private JdbcClient jdbc;
	private AcceptedAnswerKnowledgeRepository repository;
	private ExecutorService executor;

	@BeforeAll
	static void setUpSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
	}

	@BeforeEach
	void setUp() {
		dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("TRUNCATE users RESTART IDENTITY CASCADE").update();
		repository = new JdbcAcceptedAnswerKnowledgeRepository(
			jdbc,
			new DataSourceTransactionManager(dataSource),
			new AcceptedAnswerKnowledgeDocumentFactory(
				new WebQuestionPiiSanitizer(),
				new StoredAddressRegionParser()
			)
		);
	}

	@AfterEach
	void stopExecutor() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Test
	void claimsOnlyTheRequestedEligibleAnswerAndLeavesUnrelatedAnswersUntouched() {
		long requested = insertAcceptedAnswer("요청한 답변입니다.");
		long unrelated = insertAcceptedAnswer("다른 답변입니다.");

		AcceptedAnswerKnowledgeClaim claim = repository
			.claimByAnswerId(requested, LEASE, MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(claim.answerId()).isEqualTo(requested);
		assertThat(claim.questionId()).isPositive();
		assertThat(claim.attempt()).isEqualTo(1);
		assertThat(claim.leaseUntil()).isAfter(OffsetDateTime.now());
		assertThat(claim.document().chunkText()).contains("채택 답변: 요청한 답변입니다.");
		assertThat(jdbc.sql("SELECT answer_id FROM knowledge_sources ORDER BY source_id")
			.query(Long.class).list()).containsExactly(requested).doesNotContain(unrelated);
		assertThat(sourceState(requested)).containsExactly(
			"accepted_human_answer", "pending", "true", "1", "community", "accepted-answer-v1"
		);
	}

	@Test
	@Timeout(10)
	void concurrentClaimsForTheSameAnswerProduceOneSourceAndOneWinner() throws Exception {
		long answerId = insertAcceptedAnswer("동시에 한 번만 claim 되어야 합니다.");
		executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);

		Future<Optional<AcceptedAnswerKnowledgeClaim>> first = executor.submit(() -> {
			start.await(5, TimeUnit.SECONDS);
			return repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS);
		});
		Future<Optional<AcceptedAnswerKnowledgeClaim>> second = executor.submit(() -> {
			start.await(5, TimeUnit.SECONDS);
			return repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS);
		});
		start.countDown();

		assertThat(List.of(first.get(), second.get()).stream().filter(Optional::isPresent)).hasSize(1);
		assertThat(countSources(answerId)).isEqualTo(1);
		assertThat(sourceAttempts(answerId)).isEqualTo(1);
	}

	@Test
	void rejectsIneligibleAnswersWithoutCreatingWork() {
		long unaccepted = insertAnswer("미채택", false, false);
		long ai = insertAnswer("AI 답변", true, true);
		long deletedQuestion = insertAcceptedAnswer("삭제 질문");
		long deletedPin = insertAcceptedAnswer("삭제 핀");
		long wrongPinType = insertAcceptedAnswer("모임 핀");
		jdbc.sql("UPDATE questions SET deleted_at = now() WHERE question_id = (SELECT question_id FROM answers WHERE answer_id=:id)")
			.param("id", deletedQuestion).update();
		jdbc.sql("UPDATE pins SET deleted_at = now() WHERE pin_id = (SELECT q.pin_id FROM questions q JOIN answers a ON a.question_id=q.question_id WHERE a.answer_id=:id)")
			.param("id", deletedPin).update();
		jdbc.sql("UPDATE pins SET pin_type = 'meeting' WHERE pin_id = (SELECT q.pin_id FROM questions q JOIN answers a ON a.question_id=q.question_id WHERE a.answer_id=:id)")
			.param("id", wrongPinType).update();

		for (long answerId : List.of(unaccepted, ai, deletedQuestion, deletedPin, wrongPinType)) {
			assertThat(repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)).isEmpty();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_sources").query(Integer.class).single()).isZero();
	}

	@Test
	void reclaimsOnlyExpiredPendingOrDueFailedRowsWithAFreshFence() {
		long pendingAnswer = insertAcceptedAnswer("pending reclaim");
		AcceptedAnswerKnowledgeClaim first = repository.claimByAnswerId(pendingAnswer, LEASE, MAX_ATTEMPTS)
			.orElseThrow();
		assertThat(repository.claimByAnswerId(pendingAnswer, LEASE, MAX_ATTEMPTS)).isEmpty();
		jdbc.sql("UPDATE knowledge_sources SET ingestion_lease_until=now()-interval '1 second' WHERE answer_id=:id")
			.param("id", pendingAnswer).update();

		AcceptedAnswerKnowledgeClaim reclaimed = repository.claimByAnswerId(pendingAnswer, LEASE, MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(reclaimed.attempt()).isEqualTo(2);
		assertThat(reclaimed.ingestionToken()).isNotEqualTo(first.ingestionToken());
		assertThat(reclaimed.leaseUntil()).isNotEqualTo(first.leaseUntil());

		long failedAnswer = insertAcceptedAnswer("failed reclaim");
		AcceptedAnswerKnowledgeClaim failed = repository.claimByAnswerId(failedAnswer, LEASE, MAX_ATTEMPTS)
			.orElseThrow();
		assertThat(repository.markEmbeddingFailure(failed, RETRY_DELAY, MAX_ATTEMPTS)).isTrue();
		assertThat(repository.claimByAnswerId(failedAnswer, LEASE, MAX_ATTEMPTS)).isEmpty();
		jdbc.sql("UPDATE knowledge_sources SET next_attempt_at=now()-interval '1 second' WHERE answer_id=:id")
			.param("id", failedAnswer).update();
		assertThat(repository.claimByAnswerId(failedAnswer, LEASE, MAX_ATTEMPTS)).get()
			.extracting(AcceptedAnswerKnowledgeClaim::attempt).isEqualTo(2);
	}

	@Test
	void sanitizerEmptyAnswerCreatesOneDurableInactiveMarker() {
		long answerId = insertAcceptedAnswer("010-1234-5678 user@example.com");

		assertThat(repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)).isEmpty();
		assertThat(repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)).isEmpty();

		assertThat(countSources(answerId)).isEqualTo(1);
		assertThat(jdbc.sql("""
			SELECT status, active::text, display_name, deactivation_reason,
			       length(btrim(content_hash)), ingestion_attempts
			FROM knowledge_sources WHERE answer_id=:answerId
			""").param("answerId", answerId)
			.query((rs, row) -> List.of(
				rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
				Integer.toString(rs.getInt(5)), Integer.toString(rs.getInt(6))))
			.single()).containsExactly("inactive", "false", "채택된 답변", "ineligible_text", "64", "0");
	}

	@Test
	void blankAnswerAlsoConvergesToOneDurableInactiveMarker() {
		long answerId = insertAcceptedAnswer("   ");

		assertThat(repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)).isEmpty();
		assertThat(repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)).isEmpty();

		assertThat(countSources(answerId)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT status,deactivation_reason FROM knowledge_sources WHERE answer_id=:id")
			.param("id", answerId)
			.query((rs, row) -> List.of(rs.getString(1), rs.getString(2))).single())
			.containsExactly("inactive", "ineligible_text");
	}

	@Test
	void sanitizerEmptyRedispatchMakesAnExistingReadySourceInactive() {
		long answerId = insertAcceptedAnswer("010-1234-5678 user@example.com");
		insertSourceMarker(answerId, "ready", 1, null);
		long sourceId = jdbc.sql("SELECT source_id FROM knowledge_sources WHERE answer_id=:id")
			.param("id", answerId).query(Long.class).single();
		jdbc.sql("""
			INSERT INTO knowledge_chunks(source_id,content,embedding,embedding_model)
			VALUES (:sourceId,'stale content',('[1' || repeat(',0',767) || ']')::vector,'gemini-embedding-2')
			""").param("sourceId", sourceId).update();

		assertThat(repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)).isEmpty();

		assertThat(jdbc.sql("SELECT status,active::text,deactivation_reason FROM knowledge_sources WHERE answer_id=:id")
			.param("id", answerId)
			.query((rs, row) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).single())
			.containsExactly("inactive", "false", "ineligible_text");
	}

	@Test
	void adminSuppressedAndTerminalFailureRowsAreNeverClaimed() {
		long suppressed = insertAcceptedAnswer("관리자 비활성");
		insertSourceMarker(suppressed, "admin_suppressed", 0, null);
		long terminal = insertAcceptedAnswer("시도 소진");
		insertSourceMarker(terminal, "failed", MAX_ATTEMPTS, null);

		assertThat(repository.claimByAnswerId(suppressed, LEASE, MAX_ATTEMPTS)).isEmpty();
		assertThat(repository.claimByAnswerId(terminal, LEASE, MAX_ATTEMPTS)).isEmpty();
		assertThat(sourceAttempts(suppressed)).isZero();
		assertThat(sourceAttempts(terminal)).isEqualTo(MAX_ATTEMPTS);
	}

	@Test
	void finalizesExactlyOneGeminiTwoChunkBehindTheExactFence() {
		long answerId = insertAcceptedAnswer("벡터로 저장할 답변입니다.");
		AcceptedAnswerKnowledgeClaim claim = repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(repository.finalizeClaim(claim, vector(0.25f)))
			.isEqualTo(AcceptedAnswerKnowledgeFinalizeResult.READY);
		assertThat(repository.finalizeClaim(claim, vector(0.5f)))
			.isEqualTo(AcceptedAnswerKnowledgeFinalizeResult.STALE);

		assertThat(jdbc.sql("""
			SELECT ks.status, ks.active::text, ks.ingestion_token IS NULL,
			       count(kc.chunk_id), min(kc.chunk_order), min(kc.embedding_model),
			       min(vector_dims(kc.embedding)), min(kc.content)
			FROM knowledge_sources ks
			JOIN knowledge_chunks kc ON kc.source_id=ks.source_id
			WHERE ks.answer_id=:answerId
			GROUP BY ks.source_id
			""").param("answerId", answerId)
			.query((rs, row) -> List.of(
				rs.getString(1), Boolean.toString(rs.getBoolean(2)), Boolean.toString(rs.getBoolean(3)),
				Integer.toString(rs.getInt(4)), Integer.toString(rs.getInt(5)), rs.getString(6),
				Integer.toString(rs.getInt(7)), rs.getString(8)))
			.single()).containsExactly(
				"ready", "true", "true", "1", "0", "gemini-embedding-2", "768",
				claim.document().chunkText()
			);
	}

	@Test
	void staleTokenAndChangedLeaseCannotFinalizeOrFailANewerClaim() {
		long answerId = insertAcceptedAnswer("fencing 대상");
		AcceptedAnswerKnowledgeClaim stale = repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)
			.orElseThrow();
		jdbc.sql("UPDATE knowledge_sources SET ingestion_lease_until=now()-interval '1 second' WHERE answer_id=:id")
			.param("id", answerId).update();
		AcceptedAnswerKnowledgeClaim current = repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(repository.finalizeClaim(stale, vector(0.1f)))
			.isEqualTo(AcceptedAnswerKnowledgeFinalizeResult.STALE);
		assertThat(repository.markEmbeddingFailure(stale, RETRY_DELAY, MAX_ATTEMPTS)).isFalse();
		assertThat(repository.finalizeClaim(current, vector(0.2f)))
			.isEqualTo(AcceptedAnswerKnowledgeFinalizeResult.READY);

		long changedLease = insertAcceptedAnswer("lease 비교 대상");
		AcceptedAnswerKnowledgeClaim original = repository.claimByAnswerId(changedLease, LEASE, MAX_ATTEMPTS)
			.orElseThrow();
		jdbc.sql("UPDATE knowledge_sources SET ingestion_lease_until=ingestion_lease_until+interval '1 second' WHERE answer_id=:id")
			.param("id", changedLease).update();
		assertThat(repository.finalizeClaim(original, vector(0.3f)))
			.isEqualTo(AcceptedAnswerKnowledgeFinalizeResult.STALE);
	}

	@Test
	@Timeout(10)
	void finalizeThatWaitsPastLeaseExpiryCannotPublish() throws Exception {
		long answerId = insertAcceptedAnswer("잠금 대기 중 만료될 답변");
		AcceptedAnswerKnowledgeClaim claim = repository
			.claimByAnswerId(answerId, Duration.ofSeconds(1), MAX_ATTEMPTS)
			.orElseThrow();
		long questionId = claim.questionId();
		executor = Executors.newSingleThreadExecutor();

		try (Connection blocker = dataSource.getConnection();
			 PreparedStatement lockQuestion = blocker.prepareStatement(
				 "SELECT question_id FROM questions WHERE question_id=? FOR UPDATE")) {
			blocker.setAutoCommit(false);
			lockQuestion.setLong(1, questionId);
			lockQuestion.executeQuery();
			Future<AcceptedAnswerKnowledgeFinalizeResult> result = executor.submit(
				() -> repository.finalizeClaim(claim, vector(0.7f))
			);
			Thread.sleep(1_300L);
			assertThat(result).isNotDone();
			blocker.commit();

			assertThat(result.get(5, TimeUnit.SECONDS))
				.isEqualTo(AcceptedAnswerKnowledgeFinalizeResult.STALE);
		}
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_chunks").query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT status FROM knowledge_sources WHERE answer_id=:id")
			.param("id", answerId).query(String.class).single()).isEqualTo("pending");
	}

	@Test
	void finalEligibilityLossMarksTheClaimInactiveWithoutAChunk() {
		long answerId = insertAcceptedAnswer("나중에 채택 해제");
		AcceptedAnswerKnowledgeClaim claim = repository.claimByAnswerId(answerId, LEASE, MAX_ATTEMPTS)
			.orElseThrow();
		jdbc.sql("UPDATE answers SET is_accepted=false WHERE answer_id=:id").param("id", answerId).update();

		assertThat(repository.finalizeClaim(claim, vector(0.4f)))
			.isEqualTo(AcceptedAnswerKnowledgeFinalizeResult.INELIGIBLE);
		assertThat(jdbc.sql("SELECT status, active::text, deactivation_reason FROM knowledge_sources WHERE answer_id=:id")
			.param("id", answerId)
			.query((rs, row) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).single())
			.containsExactly("inactive", "false", "source_became_ineligible");
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_chunks").query(Integer.class).single()).isZero();
	}

	@Test
	void failureStoresOnlySafeFixedErrorAndTheFifthAttemptIsTerminal() {
		long retryableAnswer = insertAcceptedAnswer("재시도 답변");
		AcceptedAnswerKnowledgeClaim retryable = repository.claimByAnswerId(retryableAnswer, LEASE, MAX_ATTEMPTS)
			.orElseThrow();

		assertThat(repository.markEmbeddingFailure(retryable, RETRY_DELAY, MAX_ATTEMPTS)).isTrue();
		assertThat(failureState(retryableAnswer)).containsExactly(
			"failed", "true", "accepted_answer_embedding_failed", "Accepted answer embedding failed"
		);

		long terminalAnswer = insertAcceptedAnswer("마지막 시도 답변");
		insertSourceMarker(terminalAnswer, "failed", 4, OffsetDateTime.now().minusMinutes(1));
		AcceptedAnswerKnowledgeClaim terminal = repository.claimByAnswerId(terminalAnswer, LEASE, MAX_ATTEMPTS)
			.orElseThrow();
		assertThat(terminal.attempt()).isEqualTo(5);
		assertThat(repository.markEmbeddingFailure(terminal, RETRY_DELAY, MAX_ATTEMPTS)).isTrue();
		assertThat(failureState(terminalAnswer)).containsExactly(
			"failed", "false", "accepted_answer_embedding_failed", "Accepted answer embedding failed"
		);
		assertThat(repository.claimByAnswerId(terminalAnswer, LEASE, MAX_ATTEMPTS)).isEmpty();
	}

	private long insertAcceptedAnswer(String content) {
		return insertAnswer(content, true, false);
	}

	private long insertAnswer(String content, boolean accepted, boolean ai) {
		long questionAuthor = insertUser("question-author-" + UUID.randomUUID());
		long answerAuthor = ai ? 0L : insertUser("answer-author-" + UUID.randomUUID());
		long pinId = jdbc.sql("""
			INSERT INTO pins(author_id,pin_type,location,address,detail_address,label)
			VALUES (:authorId,'question',ST_SetSRID(ST_MakePoint(126.978,37.5665),4326)::geography,
			        '대한민국 서울특별시 중구 세종대로 110','','서울시청')
			RETURNING pin_id
			""").param("authorId", questionAuthor).query(Long.class).single();
		long questionId = jdbc.sql("""
			INSERT INTO questions(pin_id,author_id,title,content)
			VALUES (:pinId,:authorId,'버스 이용 방법','한국에서 버스를 어떻게 이용하나요?')
			RETURNING question_id
			""").param("pinId", pinId).param("authorId", questionAuthor).query(Long.class).single();
		return jdbc.sql("""
			INSERT INTO answers(question_id,author_id,is_ai,content,is_accepted)
			VALUES (:questionId,CAST(:authorId AS bigint),:isAi,:content,:accepted)
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.param("authorId", ai ? null : answerAuthor)
			.param("isAi", ai)
			.param("content", content)
			.param("accepted", accepted)
			.query(Long.class).single();
	}

	private long insertUser(String nickname) {
		return jdbc.sql("""
			INSERT INTO users(email,provider,password_hash,nickname,email_verified)
			VALUES (:email,'email','hash',:nickname,true)
			RETURNING user_id
			""")
			.param("email", nickname + "@example.com")
			.param("nickname", nickname.substring(0, Math.min(nickname.length(), 50)))
			.query(Long.class).single();
	}

	private void insertSourceMarker(long answerId, String status, int attempts, OffsetDateTime nextAttemptAt) {
		long questionId = jdbc.sql("SELECT question_id FROM answers WHERE answer_id=:id")
			.param("id", answerId).query(Long.class).single();
		jdbc.sql("""
			INSERT INTO knowledge_sources(
			  source_type,question_id,answer_id,content_hash,display_name,status,
			  ingestion_attempts,next_attempt_at,geo_scope,region_context,metadata
			)
			VALUES ('accepted_human_answer',:questionId,:answerId,:hash,'채택된 답변',:status,
			        :attempts,CAST(:nextAttemptAt AS timestamptz),'general','{}','{}')
			""")
			.param("questionId", questionId).param("answerId", answerId)
			.param("hash", "f".repeat(64)).param("status", status)
			.param("attempts", attempts).param("nextAttemptAt", nextAttemptAt).update();
	}

	private int countSources(long answerId) {
		return jdbc.sql("SELECT count(*) FROM knowledge_sources WHERE answer_id=:id")
			.param("id", answerId).query(Integer.class).single();
	}

	private int sourceAttempts(long answerId) {
		return jdbc.sql("SELECT ingestion_attempts FROM knowledge_sources WHERE answer_id=:id")
			.param("id", answerId).query(Integer.class).single();
	}

	private List<String> sourceState(long answerId) {
		return jdbc.sql("""
			SELECT source_type::text,status,(ingestion_token IS NOT NULL)::text,ingestion_attempts,
			       metadata->>'sourceGrade',metadata->>'ingestionVersion'
			FROM knowledge_sources WHERE answer_id=:id
			""").param("id", answerId)
			.query((rs, row) -> List.of(
				rs.getString(1), rs.getString(2), rs.getString(3), Integer.toString(rs.getInt(4)),
				rs.getString(5), rs.getString(6))).single();
	}

	private List<String> failureState(long answerId) {
		return jdbc.sql("""
			SELECT status,(next_attempt_at IS NOT NULL)::text,last_error_code,last_error_message
			FROM knowledge_sources WHERE answer_id=:id
			""").param("id", answerId)
			.query((rs, row) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
			.single();
	}

	private List<Float> vector(float first) {
		ArrayList<Float> values = new ArrayList<>(java.util.Collections.nCopies(768, 0.0f));
		values.set(0, first);
		return values;
	}
}
