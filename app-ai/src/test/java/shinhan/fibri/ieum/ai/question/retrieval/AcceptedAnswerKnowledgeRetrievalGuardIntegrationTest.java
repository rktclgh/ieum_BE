package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AcceptedAnswerKnowledgeRetrievalGuardIntegrationTest {

	private static final String DATABASE = "ieum_ai_accepted_guard";

	private JdbcClient jdbc;
	private JdbcVectorKnowledgeRepository vectorRepository;
	private JdbcKnowledgeGraphRepository graphRepository;

	@BeforeAll
	static void setUpSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
	}

	@BeforeEach
	void setUp() {
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		jdbc.sql("TRUNCATE users RESTART IDENTITY CASCADE").update();
		vectorRepository = new JdbcVectorKnowledgeRepository(jdbc);
		graphRepository = new JdbcKnowledgeGraphRepository(jdbc);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("eligibilityLosses")
	void acceptedSourceDisappearsImmediatelyFromVectorAndKgCandidatesAndRevalidation(
		String name,
		BiConsumer<JdbcClient, AcceptedFixture> mutation
	) {
		AcceptedFixture fixture = insertAcceptedFixture();

		assertThat(vectorRepository.findGlobalCandidates(vector(), 20))
			.extracting(VectorKnowledgeCandidate::chunkId)
			.containsExactly(fixture.chunkId());
		assertThat(graphRepository.findOneHopCandidates(List.of("서울"), 20))
			.extracting(KnowledgeGraphCandidate::relationId)
			.containsExactly(fixture.relationId());
		assertThat(vectorRepository.findEligibleChunkIds(List.of(fixture.chunkId())))
			.containsExactly(fixture.chunkId());
		assertThat(graphRepository.findEligibleRelationIds(List.of(fixture.relationId())))
			.containsExactly(fixture.relationId());

		mutation.accept(jdbc, fixture);

		assertThat(vectorRepository.findGlobalCandidates(vector(), 20)).isEmpty();
		assertThat(graphRepository.findOneHopCandidates(List.of("서울"), 20)).isEmpty();
		assertThat(vectorRepository.findEligibleChunkIds(List.of(fixture.chunkId()))).isEmpty();
		assertThat(graphRepository.findEligibleRelationIds(List.of(fixture.relationId()))).isEmpty();
	}

	private static List<Object[]> eligibilityLosses() {
		return List.of(
			new Object[]{"acceptance revoked", (BiConsumer<JdbcClient, AcceptedFixture>) (jdbc, fixture) ->
				jdbc.sql("UPDATE answers SET is_accepted=false WHERE answer_id=:id")
					.param("id", fixture.answerId()).update()},
			new Object[]{"question soft deleted", (BiConsumer<JdbcClient, AcceptedFixture>) (jdbc, fixture) ->
				jdbc.sql("UPDATE questions SET deleted_at=now() WHERE question_id=:id")
					.param("id", fixture.questionId()).update()},
			new Object[]{"pin soft deleted", (BiConsumer<JdbcClient, AcceptedFixture>) (jdbc, fixture) ->
				jdbc.sql("UPDATE pins SET deleted_at=now() WHERE pin_id=:id")
					.param("id", fixture.pinId()).update()},
			new Object[]{"answer text removed", (BiConsumer<JdbcClient, AcceptedFixture>) (jdbc, fixture) ->
				jdbc.sql("UPDATE answers SET content='   ' WHERE answer_id=:id")
					.param("id", fixture.answerId()).update()}
		);
	}

	private AcceptedFixture insertAcceptedFixture() {
		long questionAuthor = insertUser("question-author");
		long answerAuthor = insertUser("answer-author");
		long pinId = jdbc.sql("""
			INSERT INTO pins(author_id,pin_type,location,address)
			VALUES (:authorId,'question',ST_SetSRID(ST_MakePoint(126.978,37.5665),4326)::geography,
			        '대한민국 서울특별시 중구 세종대로 110')
			RETURNING pin_id
			""").param("authorId", questionAuthor).query(Long.class).single();
		long questionId = jdbc.sql("""
			INSERT INTO questions(pin_id,author_id,title,content)
			VALUES (:pinId,:authorId,'버스 질문','버스 이용 방법') RETURNING question_id
			""").param("pinId", pinId).param("authorId", questionAuthor).query(Long.class).single();
		long answerId = jdbc.sql("""
			INSERT INTO answers(question_id,author_id,is_ai,content,is_accepted)
			VALUES (:questionId,:authorId,false,'앞문으로 타고 뒷문으로 내립니다.',true)
			RETURNING answer_id
			""").param("questionId", questionId).param("authorId", answerAuthor).query(Long.class).single();
		long sourceId = jdbc.sql("""
			INSERT INTO knowledge_sources(
			  source_type,question_id,answer_id,content_hash,display_name,status,
			  ingestion_attempts,geo_scope,region_context,metadata
			)
			VALUES ('accepted_human_answer',:questionId,:answerId,:hash,'채택 답변','ready',
			        1,'general','{}',jsonb_build_object('sourceGrade','community'))
			RETURNING source_id
			""").param("questionId", questionId).param("answerId", answerId)
			.param("hash", "a".repeat(64)).query(Long.class).single();
		long chunkId = jdbc.sql("""
			INSERT INTO knowledge_chunks(source_id,content,chunk_order,embedding,embedding_model)
			VALUES (:sourceId,'서울 버스 근거',0,
			        ('[1' || repeat(',0',767) || ']')::vector,'gemini-embedding-2')
			RETURNING chunk_id
			""").param("sourceId", sourceId).query(Long.class).single();
		long relationId = jdbc.sql("""
			INSERT INTO knowledge_relations(source_id,subject,predicate,object,confidence,evidence_chunk_id)
			VALUES (:sourceId,'서울','supports','버스 이용','0.9000',:chunkId)
			RETURNING relation_id
			""").param("sourceId", sourceId).param("chunkId", chunkId).query(Long.class).single();
		return new AcceptedFixture(pinId, questionId, answerId, chunkId, relationId);
	}

	private long insertUser(String prefix) {
		String suffix = UUID.randomUUID().toString();
		return jdbc.sql("""
			INSERT INTO users(email,provider,password_hash,nickname,email_verified)
			VALUES (:email,'email','hash',:nickname,true) RETURNING user_id
			""").param("email", prefix + '-' + suffix + "@example.com")
			.param("nickname", (prefix + '-' + suffix).substring(0, 45))
			.query(Long.class).single();
	}

	private List<Float> vector() {
		ArrayList<Float> vector = new ArrayList<>(java.util.Collections.nCopies(768, 0.0f));
		vector.set(0, 1.0f);
		return vector;
	}

	private record AcceptedFixture(
		long pinId,
		long questionId,
		long answerId,
		long chunkId,
		long relationId
	) {
	}
}
