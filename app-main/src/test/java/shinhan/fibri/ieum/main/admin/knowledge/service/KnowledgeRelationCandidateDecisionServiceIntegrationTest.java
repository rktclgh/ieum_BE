package shinhan.fibri.ieum.main.admin.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateApproveRequest;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateDecisionResponse;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateListRequest;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateRejectRequest;
import shinhan.fibri.ieum.main.admin.knowledge.exception.InvalidKnowledgeCandidateStatusException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateConcurrentlyChangedException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateSourceIneligibleException;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class KnowledgeRelationCandidateDecisionServiceIntegrationTest {

	private static final String DATABASE = "ieum_main_admin_knowledge_candidate_decision";
	private static KnowledgeRelationCandidateDecisionService service;
	private static KnowledgeRelationCandidateQueryService queryService;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void setUpDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		var dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = new JdbcTemplate(dataSource);
		var transactionManager = new DataSourceTransactionManager(dataSource);
		var repository = new JdbcKnowledgeRelationCandidateAdminRepository(JdbcClient.create(dataSource));
		var target = new KnowledgeRelationCandidateDecisionService(
			repository,
			new AdminAuditLogWriter(JdbcClient.create(dataSource), new ObjectMapper().findAndRegisterModules())
		);
		service = transactionalService(target, transactionManager);
		queryService = new KnowledgeRelationCandidateQueryService(repository);
	}

	@BeforeEach
	void resetRows() {
		jdbc.execute("TRUNCATE TABLE users CASCADE");
		jdbc.update("""
			INSERT INTO users(user_id, email, password_hash, nickname, role, status)
			VALUES
				(1, 'asker@example.com', 'hash', 'asker', 'user', 'active'),
				(2, 'answerer@example.com', 'hash', 'answerer', 'user', 'active'),
				(9, 'admin@example.com', 'hash', 'admin', 'admin', 'active')
			""");
	}

	@Test
	void approvePromotesEligibleCandidateWithEvidenceAndAudit() {
		long candidateId = insertEligibleCandidate(100L, 200L, 300L, 400L, "기존 주어", "requires", "기존 목적어");

		AdminKnowledgeCandidateDecisionResponse response = service.approve(
			candidateId,
			9L,
			new AdminKnowledgeCandidateApproveRequest(
				1,
				"외국인등록",
				KnowledgeRelationPredicate.requires,
				"여권"
			)
		);

		assertThat(response.candidateId()).isEqualTo(candidateId);
		assertThat(response.status()).isEqualTo("approved");
		assertThat(response.version()).isEqualTo(2);
		assertThat(response.relation()).isNotNull();
		assertThat(response.relation().subject()).isEqualTo("외국인등록");
		assertThat(response.relation().predicate()).isEqualTo(KnowledgeRelationPredicate.requires);
		assertThat(response.relation().object()).isEqualTo("여권");
		assertThat(value("SELECT status FROM knowledge_relation_candidates WHERE candidate_id = " + candidateId))
			.isEqualTo("approved");
		assertThat(value("SELECT evidence_chunk_id FROM knowledge_relations WHERE relation_id = "
			+ response.relation().relationId())).isEqualTo(400L);
		assertAudit(
			"KNOWLEDGE_RELATION_APPROVED",
			candidateId,
			Map.of(
				"sourceId", 300,
				"relationId", response.relation().relationId().intValue(),
				"previousStatus", "pending",
				"newStatus", "approved",
				"version", 2
			)
		);
	}

	@Test
	void duplicateTripleReusesExistingRelation() {
		insertEligibleCandidate(100L, 200L, 300L, 400L, "외국인등록", "requires", "여권");
		long duplicateCandidateId = insertCandidate(300L, 401L, 501L, "외국인등록", "requires", "여권");
		long existingRelationId = service.approve(
			500L,
			9L,
			approve(1, "외국인등록", KnowledgeRelationPredicate.requires, "여권")
		).relation().relationId();

		AdminKnowledgeCandidateDecisionResponse response = service.approve(
			duplicateCandidateId,
			9L,
			approve(1, "외국인등록", KnowledgeRelationPredicate.requires, "여권")
		);

		assertThat(response.relation().relationId()).isEqualTo(existingRelationId);
		assertThat(count("""
			SELECT COUNT(*) FROM knowledge_relations
			WHERE source_id = 300 AND subject = '외국인등록' AND predicate = 'requires' AND object = '여권'
			""")).isEqualTo(1);
		assertThat(value("SELECT promotion_relation_id FROM knowledge_relation_candidates WHERE candidate_id = "
			+ duplicateCandidateId)).isEqualTo(existingRelationId);
	}

	@Test
	void staleVersionAndTerminalCandidateReturnConcurrentChange() {
		long candidateId = insertEligibleCandidate(100L, 200L, 300L, 400L, "외국인등록", "requires", "여권");

		assertThatThrownBy(() -> service.approve(
			candidateId,
			9L,
			approve(2, "외국인등록", KnowledgeRelationPredicate.requires, "여권")
		)).isInstanceOf(KnowledgeCandidateConcurrentlyChangedException.class);

		service.reject(candidateId, 9L, new AdminKnowledgeCandidateRejectRequest(1, "중복"));

		assertThatThrownBy(() -> service.approve(
			candidateId,
			9L,
			approve(2, "외국인등록", KnowledgeRelationPredicate.requires, "여권")
		)).isInstanceOf(KnowledgeCandidateConcurrentlyChangedException.class);
		assertThat(count("SELECT COUNT(*) FROM knowledge_relations")).isZero();
	}

	@Test
	void sourceIneligibilityInvalidatesPendingCandidateAndReturnsSourceIneligible() {
		long candidateId = insertEligibleCandidate(100L, 200L, 300L, 400L, "외국인등록", "requires", "여권");
		jdbc.update("UPDATE answers SET content = '   ' WHERE answer_id = 200");

		assertThatThrownBy(() -> service.approve(
			candidateId,
			9L,
			approve(1, "외국인등록", KnowledgeRelationPredicate.requires, "여권")
		)).isInstanceOf(KnowledgeCandidateSourceIneligibleException.class);

		assertThat(value("SELECT status FROM knowledge_relation_candidates WHERE candidate_id = " + candidateId))
			.isEqualTo("invalidated");
		assertThat(value("SELECT review_note FROM knowledge_relation_candidates WHERE candidate_id = " + candidateId))
			.isEqualTo("source_ineligible");
		assertThat(count("SELECT COUNT(*) FROM knowledge_relations")).isZero();
	}

	@Test
	void rejectsStalePromotedCandidateStatusFilter() {
		assertThatThrownBy(() -> queryService.list(new AdminKnowledgeCandidateListRequest("promoted", null, null)))
			.isInstanceOf(InvalidKnowledgeCandidateStatusException.class)
			.hasMessage("Knowledge candidate status must be pending, approved, rejected, or invalidated");
	}

	@Test
	void listsPendingCandidatesOnFirstAndCursorPages() {
		long firstCandidateId = insertEligibleCandidate(100L, 200L, 300L, 400L, "외국인등록", "requires", "여권");
		long secondCandidateId = insertEligibleCandidate(101L, 201L, 301L, 401L, "체류", "requires", "비자");

		var firstPage = queryService.list(new AdminKnowledgeCandidateListRequest("pending", null, 1));

		assertThat(firstPage.items()).extracting(item -> item.candidateId()).containsExactly(secondCandidateId);
		assertThat(firstPage.nextCursor()).isNotBlank();

		var nextPage = queryService.list(new AdminKnowledgeCandidateListRequest("pending", firstPage.nextCursor(), 1));

		assertThat(nextPage.items()).extracting(item -> item.candidateId()).containsExactly(firstCandidateId);
	}

	@Test
	void rejectMakesCandidateTerminalAndWritesAudit() {
		long candidateId = insertEligibleCandidate(100L, 200L, 300L, 400L, "외국인등록", "requires", "여권");

		AdminKnowledgeCandidateDecisionResponse response = service.reject(
			candidateId,
			9L,
			new AdminKnowledgeCandidateRejectRequest(1, "근거 부족")
		);

		assertThat(response.candidateId()).isEqualTo(candidateId);
		assertThat(response.status()).isEqualTo("rejected");
		assertThat(response.version()).isEqualTo(2);
		assertThat(response.relation()).isNull();
		assertThat(value("SELECT review_note FROM knowledge_relation_candidates WHERE candidate_id = " + candidateId))
			.isEqualTo("근거 부족");
		assertThatThrownBy(() -> service.reject(
			candidateId,
			9L,
			new AdminKnowledgeCandidateRejectRequest(2, "다시 반려")
		)).isInstanceOf(KnowledgeCandidateConcurrentlyChangedException.class);
		assertAudit(
			"KNOWLEDGE_RELATION_REJECTED",
			candidateId,
			Map.of(
				"sourceId", 300,
				"previousStatus", "pending",
				"newStatus", "rejected",
				"version", 2,
				"reason", "근거 부족"
			)
		);
	}

	private static AdminKnowledgeCandidateApproveRequest approve(
		int version,
		String subject,
		KnowledgeRelationPredicate predicate,
		String object
	) {
		return new AdminKnowledgeCandidateApproveRequest(version, subject, predicate, object);
	}

	private long insertEligibleCandidate(
		long questionId,
		long answerId,
		long sourceId,
		long chunkId,
		String subject,
		String predicate,
		String object
	) {
		insertEligibleSource(questionId, answerId, sourceId, chunkId);
		return insertCandidate(sourceId, chunkId, sourceId + 200L, subject, predicate, object);
	}

	private void insertEligibleSource(long questionId, long answerId, long sourceId, long chunkId) {
		jdbc.update("""
			INSERT INTO pins(pin_id, author_id, pin_type, title, latitude, longitude)
			VALUES (?, 1, 'question', '질문 핀', 37.5665, 126.9780)
			""", questionId + 1000L);
		jdbc.update("""
			INSERT INTO questions(question_id, pin_id, author_id, title, content, is_resolved)
			VALUES (?, ?, 1, '체류 질문', '외국인등록에는 무엇이 필요한가요?', true)
			""", questionId, questionId + 1000L);
		jdbc.update("""
			INSERT INTO answers(answer_id, question_id, author_id, is_ai, content, is_accepted)
			VALUES (?, ?, 2, false, '외국인등록에는 여권이 필요합니다.', true)
			""", answerId, questionId);
		jdbc.update("""
			INSERT INTO knowledge_sources(
			    source_id, source_type, question_id, answer_id, content_hash, display_name,
			    status, geo_scope, created_by, updated_by
			)
			VALUES (?, 'accepted_human_answer', ?, ?, repeat('a', 64), '외국인등록 답변',
			        'ready', 'general', 'test', 'test')
			""", sourceId, questionId, answerId);
		jdbc.update("""
			INSERT INTO knowledge_chunks(chunk_id, source_id, content, chunk_order, embedding, embedding_model)
			VALUES (?, ?, '외국인등록에는 여권이 필요합니다.', 0, CAST(? AS vector), 'gemini-embedding-2')
			""", chunkId, sourceId, zeroVector());
	}

	private long insertCandidate(
		long sourceId,
		long chunkId,
		long candidateId,
		String subject,
		String predicate,
		String object
	) {
		jdbc.update("""
			INSERT INTO knowledge_relation_candidates(
			    candidate_id, source_id, evidence_chunk_id, candidate_fingerprint,
			    subject_text, predicate, object_text, confidence, evidence_excerpt,
			    extraction_provider, extraction_model, status, version, created_by, updated_by
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, 0.9100, '외국인등록에는 여권이 필요합니다.',
			        'test-provider', 'test-model', 'pending', 1, 'test', 'test')
			""", candidateId, sourceId, chunkId, "%064x".formatted(candidateId), subject, predicate, object);
		return candidateId;
	}

	private void assertAudit(String action, long targetId, Map<String, ?> details) {
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT actor_user_id, action, target_type, target_id, details::text AS details
			FROM admin_audit_logs
			WHERE target_type = 'knowledge_relation_candidate' AND target_id = ?
			ORDER BY audit_id DESC
			LIMIT 1
			""", targetId);
		assertThat(row.get("actor_user_id")).isEqualTo(9L);
		assertThat(row.get("action")).isEqualTo(action);
		assertThat(row.get("target_type")).isEqualTo("knowledge_relation_candidate");
		assertThat(row.get("target_id")).isEqualTo(targetId);
		assertThat(row.get("details").toString()).contains(details.entrySet().stream()
			.map(entry -> "\"" + entry.getKey() + "\": " + jsonValue(entry.getValue()))
			.toArray(String[]::new));
	}

	private static String jsonValue(Object value) {
		if (value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		return "\"" + value + "\"";
	}

	private static KnowledgeRelationCandidateDecisionService transactionalService(
		KnowledgeRelationCandidateDecisionService target,
		PlatformTransactionManager transactionManager
	) {
		ProxyFactory proxyFactory = new ProxyFactory(target);
		proxyFactory.addAdvice(new TransactionInterceptor(
			transactionManager,
			new AnnotationTransactionAttributeSource()
		));
		return (KnowledgeRelationCandidateDecisionService) proxyFactory.getProxy();
	}

	private Object value(String sql) {
		return jdbc.queryForObject(sql, Object.class);
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private String zeroVector() {
		return "[" + "0.0,".repeat(767) + "0.0]";
	}

}
