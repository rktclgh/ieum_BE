package shinhan.fibri.ieum.main.admin.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeGraphRequest;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeGraphAdminRepository;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class KnowledgeGraphQueryServiceIntegrationTest {

	private static final String DATABASE = "ieum_main_admin_knowledge_graph";
	private static KnowledgeGraphQueryService service;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void setUpDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		var dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = new JdbcTemplate(dataSource);
		service = new KnowledgeGraphQueryService(new JdbcKnowledgeGraphAdminRepository(JdbcClient.create(dataSource)));
	}

	@BeforeEach
	void resetRows() {
		jdbc.execute("TRUNCATE TABLE users CASCADE");
		jdbc.update("""
			INSERT INTO users(user_id, email, password_hash, nickname, role, status)
			VALUES
				(1, 'asker@example.com', 'hash', 'asker', 'user', 'active'),
				(2, 'answerer@example.com', 'hash', 'answerer', 'user', 'active')
			""");
	}

	@Test
	void returnsOnlyEligibleAcceptedHumanAnswerGraphWithFiltersAndTruncation() {
		insertRelation(100L, 200L, 300L, 400L, "외국인등록", "requires", "여권", true, "ready", true, false);
		insertRelation(101L, 201L, 301L, 401L, "외국인등록", "applies_to", "체류자격", true, "ready", true, false);
		insertRelation(102L, 202L, 302L, 402L, "비자", "depends_on", "여권", true, "ready", true, false);
		insertRelation(103L, 203L, 303L, 403L, "수수료 100%_감면", "supports", "신청서", true, "ready", true, false);
		insertRelation(104L, 204L, 304L, 404L, "수수료 100xx감면", "supports", "비교대상", true, "ready", true, false);
		insertRelation(105L, 205L, 305L, 405L, "비활성", "requires", "제외", false, "ready", true, false);
		insertRelation(106L, 206L, 306L, 406L, "AI답변", "requires", "제외", true, "ready", true, true);
		insertRelation(107L, 207L, 307L, 407L, "미채택", "requires", "제외", true, "ready", false, false);

		var truncated = service.graph(new AdminKnowledgeGraphRequest(null, null, null, 1));

		assertThat(truncated.truncated()).isTrue();
		assertThat(truncated.edges()).hasSize(1);
		assertThat(truncated.edges().getFirst().source()).isEqualTo("수수료 100xx감면");
		assertThat(truncated.nodes()).extracting(node -> node.label())
			.containsExactlyInAnyOrder("수수료 100xx감면", "비교대상");
		assertThat(truncated.nodes()).extracting(node -> node.degree()).containsOnly(1);

		var focus = service.graph(new AdminKnowledgeGraphRequest(null, "외국인등록", null, 80));

		assertThat(focus.truncated()).isFalse();
		assertThat(focus.edges()).extracting(edge -> edge.source())
			.containsExactly("외국인등록", "외국인등록");
		assertThat(focus.edges()).extracting(edge -> edge.target())
			.containsExactly("체류자격", "여권");
		assertThat(focus.nodes()).filteredOn(node -> node.id().equals("외국인등록"))
			.singleElement()
			.extracting(node -> node.degree())
			.isEqualTo(2);

		var predicate = service.graph(new AdminKnowledgeGraphRequest(null, null, KnowledgeRelationPredicate.depends_on, 80));

		assertThat(predicate.edges()).singleElement()
			.satisfies(edge -> {
				assertThat(edge.source()).isEqualTo("비자");
				assertThat(edge.target()).isEqualTo("여권");
				assertThat(edge.predicate()).isEqualTo(KnowledgeRelationPredicate.depends_on);
				assertThat(edge.sourceDisplayName()).isEqualTo("비자 답변");
				assertThat(edge.evidencePreview()).isEqualTo("비자에는 여권이 필요합니다.");
			});

		var literalQuery = service.graph(new AdminKnowledgeGraphRequest("100%_", null, null, 80));

		assertThat(literalQuery.edges()).singleElement()
			.satisfies(edge -> {
				assertThat(edge.source()).isEqualTo("수수료 100%_감면");
				assertThat(edge.target()).isEqualTo("신청서");
			});
	}

	private void insertRelation(
		long questionId,
		long answerId,
		long sourceId,
		long chunkId,
		String subject,
		String predicate,
		String object,
		boolean sourceActive,
		String sourceStatus,
		boolean accepted,
		boolean aiAnswer
	) {
		insertSource(questionId, answerId, sourceId, chunkId, subject, sourceActive, sourceStatus, accepted, aiAnswer);
		jdbc.update("""
			INSERT INTO knowledge_relations(source_id, subject, predicate, object, confidence, evidence_chunk_id)
			VALUES (?, ?, ?, ?, ?, ?)
			""", sourceId, subject, predicate, object, new BigDecimal("0.9100"), chunkId);
	}

	private void insertSource(
		long questionId,
		long answerId,
		long sourceId,
		long chunkId,
		String subject,
		boolean sourceActive,
		String sourceStatus,
		boolean accepted,
		boolean aiAnswer
	) {
		jdbc.update("""
			INSERT INTO pins(pin_id, author_id, pin_type, title, latitude, longitude)
			VALUES (?, 1, 'question', '질문 핀', 37.5665, 126.9780)
			""", questionId + 1000L);
		jdbc.update("""
			INSERT INTO questions(question_id, pin_id, author_id, title, content, is_resolved)
			VALUES (?, ?, 1, ?, '무엇이 필요한가요?', true)
			""", questionId, questionId + 1000L, subject + " 질문");
		jdbc.update("""
			INSERT INTO answers(answer_id, question_id, author_id, is_ai, content, is_accepted)
			VALUES (?, ?, 2, ?, ?, ?)
			""", answerId, questionId, aiAnswer, subject + "에는 여권이 필요합니다.", accepted);
		jdbc.update("""
			INSERT INTO knowledge_sources(
			    source_id, source_type, question_id, answer_id, content_hash, display_name,
			    status, geo_scope, active, created_by, updated_by
			)
			VALUES (?, 'accepted_human_answer', ?, ?, ?, ?, ?, 'general', ?, 'test', 'test')
			""", sourceId, questionId, answerId, "%064x".formatted(sourceId), subject + " 답변", sourceStatus, sourceActive);
		jdbc.update("""
			INSERT INTO knowledge_chunks(chunk_id, source_id, content, chunk_order, embedding, embedding_model)
			VALUES (?, ?, ?, 0, CAST(? AS vector), 'gemini-embedding-2')
			""", chunkId, sourceId, subject + "에는 여권이 필요합니다.", zeroVector());
	}

	private String zeroVector() {
		return "[" + "0.0,".repeat(767) + "0.0]";
	}
}
