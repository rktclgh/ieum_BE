package shinhan.fibri.ieum.ai.knowledge.relations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeDocument;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class KnowledgeRelationCandidateExtractionServiceTest {

	private static final String DATABASE = "ieum_ai_knowledge_relation_candidates";
	private static final Duration LEASE = Duration.ofMinutes(5);
	private static final Duration RETRY_DELAY = Duration.ofMinutes(2);
	private static final int MAX_ATTEMPTS = 2;

	private JdbcClient jdbc;
	private KnowledgeRelationCandidateRepository repository;
	private FakeExtractor extractor;
	private KnowledgeRelationCandidateExtractionService service;

	@BeforeAll
	static void setUpSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
	}

	@BeforeEach
	void setUp() {
		DataSource dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("TRUNCATE users RESTART IDENTITY CASCADE").update();
		repository = new JdbcKnowledgeRelationCandidateRepository(
			jdbc,
			new DataSourceTransactionManager(dataSource)
		);
		extractor = new FakeExtractor();
		service = new KnowledgeRelationCandidateExtractionService(
			repository,
			extractor,
			LEASE,
			RETRY_DELAY,
			MAX_ATTEMPTS
		);
	}

	@Test
	void persistsAtMostFivePendingCandidatesFromSanitizedEvidence() {
		long sourceId = insertReadyAcceptedAnswerSource("무인민원발급기는 주민센터에서 사용할 수 있습니다.");
		repository.enqueue(sourceId);
		extractor.next(CandidateExtractionResult.valid(List.of(
			candidate("무인민원발급기", KnowledgeRelationPredicate.located_in, "주민센터", "주민센터에서 사용할 수 있습니다"),
			candidate("무인민원발급기", KnowledgeRelationPredicate.used_for, "증명서 발급", "무인민원발급기는 주민센터에서 사용할 수 있습니다"),
			candidate("증명서 발급", KnowledgeRelationPredicate.requires, "신분증", "주민센터에서 사용할 수 있습니다"),
			candidate("민원인", KnowledgeRelationPredicate.applies_to, "주민센터", "주민센터에서 사용할 수 있습니다"),
			candidate("주민센터", KnowledgeRelationPredicate.supports, "민원 처리", "주민센터에서 사용할 수 있습니다"),
			candidate("여섯번째", KnowledgeRelationPredicate.depends_on, "저장 제외", "주민센터에서 사용할 수 있습니다")
		)));

		service.processNext();

		assertThat(taskState(sourceId)).containsExactly("completed", "1", null);
		assertThat(jdbc.sql("""
			SELECT predicate, subject_text, object_text, evidence_excerpt, status
			FROM knowledge_relation_candidates
			WHERE source_id = :sourceId
			ORDER BY candidate_id
			""")
			.param("sourceId", sourceId)
			.query((rs, row) -> List.of(
				rs.getString("predicate"),
				rs.getString("subject_text"),
				rs.getString("object_text"),
				rs.getString("evidence_excerpt"),
				rs.getString("status")
			))
			.list()).hasSize(5)
			.allSatisfy(row -> assertThat(row.get(4)).isEqualTo("pending"));
	}

	@Test
	void invalidPredicateOrEvidenceCompletesTaskWithoutCandidates() {
		long invalidPredicate = insertReadyAcceptedAnswerSource("예약 변경은 접수처에 보고해야 합니다.");
		long invalidEvidence = insertReadyAcceptedAnswerSource("마감일은 매월 25일입니다.");
		repository.enqueue(invalidPredicate);
		repository.enqueue(invalidEvidence);
		extractor.next(CandidateExtractionResult.valid(List.of(
			new ExtractedKnowledgeRelationCandidate(
				"예약 변경",
				"unknown",
				"접수처",
				0.82,
				"접수처에 보고해야 합니다"
			)
		)));
		extractor.next(CandidateExtractionResult.valid(List.of(
			candidate("신청", KnowledgeRelationPredicate.has_deadline, "25일", "원문에 없는 증거")
		)));

		service.processNext();
		service.processNext();

		assertThat(taskState(invalidPredicate)).containsExactly(
			"completed", "1", "invalid_extraction_output"
		);
		assertThat(taskState(invalidEvidence)).containsExactly(
			"completed", "1", "invalid_extraction_output"
		);
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_relation_candidates")
			.query(Integer.class).single()).isZero();
	}

	@Test
	void acceptsCanonicalEquivalentEvidenceAndPersistsTheDocumentSubstring() {
		String documentEvidence = Normalizer.normalize("주민센터", Normalizer.Form.NFD);
		long sourceId = insertReadyAcceptedAnswerSource("접수는 " + documentEvidence + "에서 합니다.");
		repository.enqueue(sourceId);
		extractor.next(CandidateExtractionResult.valid(List.of(
			candidate("접수", KnowledgeRelationPredicate.located_in, "주민센터", "주민센터")
		)));

		service.processNext();

		assertThat(taskState(sourceId)).containsExactly("completed", "1", null);
		assertThat(jdbc.sql("""
			SELECT evidence_excerpt
			FROM knowledge_relation_candidates
			WHERE source_id = :sourceId
			""")
			.param("sourceId", sourceId)
			.query(String.class)
			.single()).isEqualTo(documentEvidence)
			.isNotEqualTo("주민센터");
	}

	@Test
	void providerFailureRetriesThenDeadWithoutChangingReadyVectorSource() {
		long sourceId = insertReadyAcceptedAnswerSource("폐건전지는 주민센터 수거함에 배출합니다.");
		repository.enqueue(sourceId);
		extractor.failNext(new KnowledgeRelationExtractionProviderException("timeout"));

		service.processNext();

		assertThat(taskState(sourceId)).containsExactly(
			"retry", "1", "relation_extraction_provider_failed"
		);
		assertThat(sourceAndChunkState(sourceId)).containsExactly("ready", "1", "gemini-embedding-2");

		jdbc.sql("UPDATE knowledge_relation_extraction_tasks SET next_attempt_at = now() - interval '1 second'")
			.update();
		extractor.failNext(new KnowledgeRelationExtractionProviderException("timeout"));

		service.processNext();

		assertThat(taskState(sourceId)).containsExactly(
			"dead", "2", "relation_extraction_provider_failed"
		);
		assertThat(sourceAndChunkState(sourceId)).containsExactly("ready", "1", "gemini-embedding-2");
	}

	@Test
	void rejectsEvidenceLongerThanTwoHundredCodePointsInServiceAndSchema() {
		String overlongEvidence = "\uD83D\uDE00".repeat(201);
		long sourceId = insertReadyAcceptedAnswerSource(overlongEvidence);
		repository.enqueue(sourceId);
		extractor.next(CandidateExtractionResult.valid(List.of(
			candidate("신청", KnowledgeRelationPredicate.requires, "신분증", overlongEvidence)
		)));

		service.processNext();

		assertThat(overlongEvidence.codePointCount(0, overlongEvidence.length())).isEqualTo(201);
		assertThat(taskState(sourceId)).containsExactly(
			"completed", "1", "invalid_extraction_output"
		);
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO knowledge_relation_candidates(
			    source_id, evidence_chunk_id, candidate_fingerprint,
			    subject_text, predicate, object_text, confidence, evidence_excerpt,
			    extraction_provider, extraction_model, status, created_by, updated_by
			)
			SELECT :sourceId, chunk_id, repeat('b', 64),
			       '신청', 'requires', '신분증', 0.91, :evidence,
			       'test', 'test', 'pending', 'test', 'test'
			FROM knowledge_chunks
			WHERE source_id = :sourceId AND chunk_order = 0
			""")
			.param("sourceId", sourceId)
			.param("evidence", overlongEvidence)
			.update()).isInstanceOf(DataIntegrityViolationException.class);
		String validEvidence = overlongEvidence.substring(0, overlongEvidence.offsetByCodePoints(0, 200));
		assertThat(jdbc.sql("""
			INSERT INTO knowledge_relation_candidates(
			    source_id, evidence_chunk_id, candidate_fingerprint,
			    subject_text, predicate, object_text, confidence, evidence_excerpt,
			    extraction_provider, extraction_model, status, created_by, updated_by
			)
			SELECT :sourceId, chunk_id, repeat('c', 64),
			       '신청', 'requires', '신분증', 0.91, :evidence,
			       'test', 'test', 'invalidated', 'test', 'test'
			FROM knowledge_chunks
			WHERE source_id = :sourceId AND chunk_order = 0
			""")
			.param("sourceId", sourceId)
			.param("evidence", validEvidence)
			.update()).isOne();
	}

	@Test
	void invalidatesTasksWhenAnyExtractionEligibilityRequirementFailsAndContinuesToTheNextEligibleTask() {
		long inactiveStatusSourceId = insertReadyAcceptedAnswerSource("접수는 주민센터에서 합니다.");
		long inactiveSourceId = insertReadyAcceptedAnswerSource("접수는 온라인에서 합니다.");
		long expiredSourceId = insertReadyAcceptedAnswerSource("발급은 주민센터에서 합니다.");
		long unacceptedAnswerSourceId = insertReadyAcceptedAnswerSource("신청은 온라인에서 합니다.");
		long aiAnswerSourceId = insertReadyAcceptedAnswerSource("증명서는 주민센터에서 발급합니다.");
		long blankAnswerSourceId = insertReadyAcceptedAnswerSource("예약은 전화로 확인합니다.");
		long deletedQuestionSourceId = insertReadyAcceptedAnswerSource("문의는 게시판에 남깁니다.");
		long deletedPinSourceId = insertReadyAcceptedAnswerSource("상담은 주민센터에서 가능합니다.");
		long meetingPinSourceId = insertReadyAcceptedAnswerSource("교육은 주민센터에서 진행합니다.");
		long missingChunkSourceId = insertReadyAcceptedAnswerSource("신청은 모바일에서도 가능합니다.");
		long eligibleSourceId = insertReadyAcceptedAnswerSource("발급은 주민센터에서 합니다.");
		List.of(
			inactiveStatusSourceId,
			inactiveSourceId,
			expiredSourceId,
			unacceptedAnswerSourceId,
			aiAnswerSourceId,
			blankAnswerSourceId,
			deletedQuestionSourceId,
			deletedPinSourceId,
			meetingPinSourceId,
			missingChunkSourceId,
			eligibleSourceId
		).forEach(repository::enqueue);
		jdbc.sql("UPDATE knowledge_sources SET status = 'inactive' WHERE source_id = :sourceId")
			.param("sourceId", inactiveStatusSourceId)
			.update();
		jdbc.sql("UPDATE knowledge_sources SET active = false WHERE source_id = :sourceId")
			.param("sourceId", inactiveSourceId)
			.update();
		jdbc.sql("""
			UPDATE knowledge_sources
			SET valid_until = clock_timestamp() - interval '1 second'
			WHERE source_id = :sourceId
			""").param("sourceId", expiredSourceId).update();
		jdbc.sql("""
			UPDATE answers
			SET is_accepted = false
			WHERE answer_id = (SELECT answer_id FROM knowledge_sources WHERE source_id = :sourceId)
			""").param("sourceId", unacceptedAnswerSourceId).update();
		jdbc.sql("""
			UPDATE answers
			SET is_ai = true, author_id = NULL
			WHERE answer_id = (SELECT answer_id FROM knowledge_sources WHERE source_id = :sourceId)
			""").param("sourceId", aiAnswerSourceId).update();
		jdbc.sql("""
			UPDATE answers
			SET content = '   '
			WHERE answer_id = (SELECT answer_id FROM knowledge_sources WHERE source_id = :sourceId)
			""").param("sourceId", blankAnswerSourceId).update();
		jdbc.sql("""
			UPDATE questions
			SET deleted_at = clock_timestamp()
			WHERE question_id = (SELECT question_id FROM knowledge_sources WHERE source_id = :sourceId)
			""").param("sourceId", deletedQuestionSourceId).update();
		jdbc.sql("""
			UPDATE pins
			SET deleted_at = clock_timestamp()
			WHERE pin_id = (
			    SELECT question.pin_id
			    FROM questions question
			    JOIN knowledge_sources source ON source.question_id = question.question_id
			    WHERE source.source_id = :sourceId
			)
			""").param("sourceId", deletedPinSourceId).update();
		jdbc.sql("""
			UPDATE pins
			SET pin_type = 'meeting'
			WHERE pin_id = (
			    SELECT question.pin_id
			    FROM questions question
			    JOIN knowledge_sources source ON source.question_id = question.question_id
			    WHERE source.source_id = :sourceId
			)
			""").param("sourceId", meetingPinSourceId).update();
		jdbc.sql("DELETE FROM knowledge_chunks WHERE source_id = :sourceId")
			.param("sourceId", missingChunkSourceId)
			.update();
		extractor.next(CandidateExtractionResult.valid(List.of(
			candidate("발급", KnowledgeRelationPredicate.located_in, "주민센터", "발급은 주민센터에서 합니다")
		)));

		assertThat(service.processNext()).isTrue();

		assertTasksInvalidated(
			inactiveStatusSourceId,
			inactiveSourceId,
			expiredSourceId,
			unacceptedAnswerSourceId,
			aiAnswerSourceId,
			blankAnswerSourceId,
			deletedQuestionSourceId,
			deletedPinSourceId,
			meetingPinSourceId,
			missingChunkSourceId
		);
		assertThat(taskState(eligibleSourceId)).containsExactly("completed", "1", null);
	}

	@Test
	void boundedRecoveryDrainsDurablePendingAndRetryTasksAfterSaturationAndRestart() {
		long pendingSourceId = insertReadyAcceptedAnswerSource("접수는 주민센터에서 합니다.");
		long retrySourceId = insertReadyAcceptedAnswerSource("신청은 온라인에서 합니다.");
		repository.enqueue(pendingSourceId);
		repository.enqueue(retrySourceId);
		jdbc.sql("""
			UPDATE knowledge_relation_extraction_tasks
			SET status = 'retry', next_attempt_at = clock_timestamp() - interval '1 second'
			WHERE source_id = :sourceId
			""")
			.param("sourceId", retrySourceId)
			.update();
		extractor.next(CandidateExtractionResult.valid(List.of(
			candidate("접수", KnowledgeRelationPredicate.located_in, "주민센터", "접수는 주민센터에서 합니다")
		)));
		extractor.next(CandidateExtractionResult.valid(List.of(
			candidate("신청", KnowledgeRelationPredicate.used_for, "온라인", "신청은 온라인에서 합니다")
		)));

		KnowledgeRelationCandidateTaskLane saturatedLane = new KnowledgeRelationCandidateTaskLane(
			true,
			command -> { throw new RejectedExecutionException("saturated"); },
			service
		);
		assertThat(saturatedLane.submit()).isFalse();
		assertThat(taskState(pendingSourceId)).containsExactly("pending", "0", null);

		KnowledgeRelationCandidateTaskRecovery recovery = new KnowledgeRelationCandidateTaskRecovery(
			new KnowledgeRelationCandidateTaskLane(true, Runnable::run, service),
			1
		);
		recovery.drain();

		assertThat(taskState(pendingSourceId)).containsExactly("completed", "1", null);
		assertThat(taskState(retrySourceId)).containsExactly("retry", "0", null);

		recovery.drain();

		assertThat(taskState(retrySourceId)).containsExactly("completed", "1", null);
	}

	private ExtractedKnowledgeRelationCandidate candidate(
		String subject,
		KnowledgeRelationPredicate predicate,
		String object,
		String evidence
	) {
		return new ExtractedKnowledgeRelationCandidate(
			subject,
			predicate.name(),
			object,
			0.91,
			evidence
		);
	}

	private long insertReadyAcceptedAnswerSource(String content) {
		long userId = jdbc.sql("""
			INSERT INTO users(email, provider, password_hash, nickname, email_verified)
			VALUES ('user' || nextval('users_user_id_seq') || '@example.com', 'email', 'hash',
			        'tester-' || currval('users_user_id_seq'), true)
			RETURNING user_id
			""").query(Long.class).single();
		long pinId = jdbc.sql("""
			INSERT INTO pins(author_id, pin_type, address, detail_address, label, location)
			VALUES (:userId, 'question', '서울시 중구', '', '주민센터', ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography)
			RETURNING pin_id
			""").param("userId", userId).query(Long.class).single();
		long questionId = jdbc.sql("""
			INSERT INTO questions(author_id, pin_id, title, content)
			VALUES (:userId, :pinId, '민원 안내', '질문 내용')
			RETURNING question_id
			""").param("userId", userId).param("pinId", pinId).query(Long.class).single();
		long answerId = jdbc.sql("""
			INSERT INTO answers(question_id, author_id, content, is_accepted, is_ai)
			VALUES (:questionId, :userId, :content, true, false)
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.param("userId", userId)
			.param("content", content)
			.query(Long.class)
			.single();
		long sourceId = jdbc.sql("""
			INSERT INTO knowledge_sources(
			    source_type, question_id, answer_id, content_hash, display_name, status,
			    ingestion_attempts, geo_scope, region_context, anchor_location, metadata,
			    created_by, updated_by
			)
			VALUES (
			    'accepted_human_answer', :questionId, :answerId, repeat('a', 64), '민원 안내', 'ready',
			    1, 'general', '{}', ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
			    jsonb_build_object('sourceGrade', 'community', 'ingestionVersion', 'accepted-answer-v1'),
			    'test', 'test'
			)
			RETURNING source_id
			""")
			.param("questionId", questionId)
			.param("answerId", answerId)
			.query(Long.class)
			.single();
		jdbc.sql("""
			INSERT INTO knowledge_chunks(source_id, content, chunk_order, metadata, embedding, embedding_model)
			VALUES (:sourceId, :content, 0, '{}', ('[1' || repeat(',0',767) || ']')::vector, 'gemini-embedding-2')
			""")
			.param("sourceId", sourceId)
			.param("content", "질문 제목: 민원 안내\n채택 답변: " + content)
			.update();
		return sourceId;
	}

	private List<String> taskState(long sourceId) {
		return jdbc.sql("""
			SELECT status, attempts::text, last_error_code
			FROM knowledge_relation_extraction_tasks
				WHERE source_id = :sourceId
				""").param("sourceId", sourceId)
				.query((rs, row) -> Arrays.asList(
					rs.getString("status"),
					rs.getString("attempts"),
					rs.getString("last_error_code")
				))
				.single();
	}

	private void assertTasksInvalidated(long... sourceIds) {
		for (long sourceId : sourceIds) {
			assertThat(taskState(sourceId)).containsExactly(
				"invalidated", "0", "relation_source_ineligible_or_missing_chunk"
			);
		}
	}

	private List<String> sourceAndChunkState(long sourceId) {
		return jdbc.sql("""
			SELECT ks.status, count(kc.chunk_id)::text, min(kc.embedding_model)
			FROM knowledge_sources ks
			JOIN knowledge_chunks kc ON kc.source_id = ks.source_id
			WHERE ks.source_id = :sourceId
			GROUP BY ks.source_id
			""").param("sourceId", sourceId)
			.query((rs, row) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)))
			.single();
	}

	private static final class FakeExtractor implements KnowledgeRelationCandidateExtractor {

		private final Queue<Object> results = new ArrayDeque<>();

		void next(CandidateExtractionResult result) {
			results.add(result);
		}

		void failNext(RuntimeException exception) {
			results.add(exception);
		}

		@Override
		public CandidateExtractionResult extract(AcceptedAnswerKnowledgeDocument document) {
			Object result = results.remove();
			if (result instanceof RuntimeException exception) {
				throw exception;
			}
			return (CandidateExtractionResult) result;
		}
	}
}
