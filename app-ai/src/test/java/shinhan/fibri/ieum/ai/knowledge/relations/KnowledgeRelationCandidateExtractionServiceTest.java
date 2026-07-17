package shinhan.fibri.ieum.ai.knowledge.relations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
