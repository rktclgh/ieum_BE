package shinhan.fibri.ieum.main.answer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerRequest;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerResponse;
import shinhan.fibri.ieum.main.answer.dto.FinalizeAcceptedAnswersRequest;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

/**
 * Task 3 브리프: 답변 채택 트랜잭션 커밋 시 {@code ai_job_outbox}에
 * {@code job_type=accepted_answer_knowledge_ingest}, {@code job_key=answerId} row가 동반 커밋되는지 확인한다
 * (spec.md §7.3/§8.1). Task 8 이후 {@code app.ai.dispatch.transport}의 기본값이 {@code http}(=outbox
 * writer 가 no-op)로 바뀌었으므로, 실제로 row 가 쓰이는지 검증하려면 {@code rabbitmq}로 명시해야 한다.
 */
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.ai.dispatch.transport=rabbitmq"
})
class AcceptedAnswerOutboxTransactionTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "accepted_answer_outbox_transaction");
	}

	@Autowired
	private AnswerService answerService;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private AiJobOutboxRepository outboxRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private Long questionAuthorId;
	private Long answerAuthorId;
	private Long questionId;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM ai_job_outbox").update();
		jdbc.sql("DELETE FROM answers").update();
		jdbc.sql("DELETE FROM questions").update();
		jdbc.sql("DELETE FROM pins").update();
		jdbc.sql("DELETE FROM users").update();

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		questionAuthorId = insertUser("outbox-q-author-" + suffix);
		answerAuthorId = insertUser("outbox-a-author-" + suffix);

		Long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:authorId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, :address)
			RETURNING pin_id
			""")
			.param("authorId", questionAuthorId)
			.param("address", "서울특별시")
			.query(Long.class)
			.single();
		questionId = jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, :authorId, 'title', 'content')
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("authorId", questionAuthorId)
			.query(Long.class)
			.single();
	}

	@Test
	void finalizeSelectionCommitsAcceptedAnswerKnowledgeOutboxRowWithTheAcceptedAnswer() {
		CreateAnswerResponse created = answerService.create(
			new AuthenticatedUser(answerAuthorId, "answer-author@example.com", UserRole.user, UserStatus.active),
			questionId,
			new CreateAnswerRequest("human answer", List.of())
		);
		Long answerId = created.answerId();

		answerService.finalizeSelection(
			new AuthenticatedUser(questionAuthorId, "question-author@example.com", UserRole.user, UserStatus.active),
			questionId,
			new FinalizeAcceptedAnswersRequest(List.of(answerId))
		);

		List<AiJobOutbox> rows = outboxRepository.findAll().stream()
			.filter(row -> row.getJobKey().equals(answerId))
			.toList();
		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.getJobType()).isEqualTo("accepted_answer_knowledge_ingest");
			assertThat(row.getRoutingKey()).isEqualTo("ai.accepted-answer.ingest");
			assertThat(row.getStatus()).isEqualTo("pending");
			JsonNode payload = readPayload(row);
			assertThat(payload.get("answerId").asLong()).isEqualTo(answerId);
		});
	}

	private JsonNode readPayload(AiJobOutbox row) {
		try {
			return objectMapper.readTree(row.getPayload());
		}
		catch (Exception exception) {
			throw new IllegalStateException("Failed to parse outbox payload: " + row.getPayload(), exception);
		}
	}

	private Long insertUser(String nickname) {
		return jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", nickname + "@example.com")
			.param("nickname", nickname)
			.query(Long.class)
			.single();
	}
}
