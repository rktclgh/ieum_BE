package shinhan.fibri.ieum.main.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;
import shinhan.fibri.ieum.main.question.dto.QuestionCreateRequest;
import shinhan.fibri.ieum.main.question.dto.QuestionDetailResponse;
import shinhan.fibri.ieum.main.question.dto.QuestionUpdateRequest;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

/**
 * Task 3 브리프: {@code QuestionService.create()}/{@code update()}가 도메인 변경과 같은 트랜잭션 안에서
 * {@code ai_job_outbox} row를 쓰는지 확인한다(spec.md §7.3/§8.1). 실제 Postgres 위에서 커밋/롤백을
 * 관찰해야 하므로 Testcontainers + 전체 앱 컨텍스트를 쓴다. Task 8 이후 {@code app.ai.dispatch.transport}의
 * 기본값이 {@code http}(=outbox writer 가 no-op)로 바뀌었으므로, 실제로 row 가 쓰이는지 검증하려면
 * {@code rabbitmq}로 명시해야 한다.
 */
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.ai.dispatch.transport=rabbitmq"
})
class QuestionServiceOutboxTransactionTest {

	private static final LocationSnapshot LOCATION =
		new LocationSnapshot(37.5, 127.0, "서울특별시", "", "라벨");

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "question_service_outbox_transaction");
	}

	@Autowired
	private QuestionService questionService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private AiJobOutboxRepository outboxRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private Long authorId;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM ai_job_outbox").update();
		jdbc.sql("DELETE FROM ai_question_tasks").update();
		jdbc.sql("DELETE FROM questions").update();
		jdbc.sql("DELETE FROM pins").update();
		jdbc.sql("DELETE FROM users").update();
		String suffix = UUID.randomUUID().toString();
		authorId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "outbox-question-" + suffix + "@example.com")
			.param("nickname", "outbox-q-" + suffix.substring(0, 8))
			.query(Long.class)
			.single();
	}

	@Test
	void createCommitsQuestionTicketAndOutboxRowInTheSameTransaction() {
		QuestionDetailResponse response = questionService.create(principal(), createRequest("title", "content"));
		Long questionId = response.questionId();

		assertThat(countWhere("questions", "question_id", questionId)).isEqualTo(1L);
		assertThat(countWhere("ai_question_tasks", "question_id", questionId)).isEqualTo(1L);

		List<AiJobOutbox> rows = outboxRowsFor(questionId);
		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.getJobType()).isEqualTo("question_answer_dispatch");
			assertThat(row.getRoutingKey()).isEqualTo("ai.question-answer.dispatch");
			assertThat(row.getStatus()).isEqualTo("pending");
			JsonNode payload = readPayload(row);
			assertThat(payload.get("reason").asText()).isEqualTo("created");
			assertThat(payload.get("questionId").asLong()).isEqualTo(questionId);
		});
	}

	@Test
	void createRollsBackQuestionTicketAndOutboxTogetherWhenCommitFails() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		AuthenticatedUser principal = principal();
		QuestionCreateRequest request = createRequest("title", "content");

		assertThatThrownBy(() -> transaction.execute(status -> {
			questionService.create(principal, request);
			throw new IllegalStateException("boom-before-commit");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count("questions")).isZero();
		assertThat(count("ai_question_tasks")).isZero();
		assertThat(outboxRepository.count()).isZero();
	}

	@Test
	void updateWritesOneRegeneratedOutboxRowWhenRegenerationIsRequested() {
		Long questionId = createQuestion();
		long before = outboxRepository.count();

		questionService.update(principal(), questionId, updateRequest());

		List<AiJobOutbox> rows = outboxRowsFor(questionId).stream()
			.filter(row -> "regenerated".equals(readPayload(row).get("reason").asText()))
			.toList();
		assertThat(rows).hasSize(1);
		assertThat(outboxRepository.count()).isEqualTo(before + 1);
	}

	@Test
	void updateWritesNoOutboxRowWhenTicketIsAlreadyCompleted() {
		Long questionId = createQuestion();
		markTicketCompletedWithInsufficientEvidence(questionId);
		long before = outboxRepository.count();

		questionService.update(principal(), questionId, updateRequest());

		assertThat(outboxRepository.count()).isEqualTo(before);
	}

	private Long createQuestion() {
		return questionService.create(principal(), createRequest("orig title", "orig content")).questionId();
	}

	private AuthenticatedUser principal() {
		return new AuthenticatedUser(authorId, "outbox-question@example.com", UserRole.user, UserStatus.active);
	}

	private QuestionCreateRequest createRequest(String title, String content) {
		return new QuestionCreateRequest(title, content, LOCATION, List.of());
	}

	private QuestionUpdateRequest updateRequest() {
		return new QuestionUpdateRequest("new title", "new content", List.of());
	}

	/**
	 * {@code ck_ai_question_tasks_completed} CHECK을 만족하는 최소 형태로 티켓을 completed로 만든다
	 * (db/schema.sql). {@code answer_outcome='insufficient_evidence'}는 {@code answer_id IS NULL}만
	 * 요구해 가장 적은 컬럼으로 제약을 통과한다.
	 */
	private void markTicketCompletedWithInsufficientEvidence(Long questionId) {
		String zeroVector = "[" + IntStream.range(0, 768).mapToObj(i -> "0").collect(Collectors.joining(",")) + "]";
		jdbc.sql("""
			UPDATE ai_question_tasks
			   SET status = 'completed',
			       completed_at = now(),
			       embedding = CAST(:embedding AS vector),
			       embedding_model = 'gemini-embedding-2',
			       answer_outcome = 'insufficient_evidence',
			       grounding_status = 'insufficient_evidence',
			       answer_id = NULL
			 WHERE question_id = :id
			""")
			.param("embedding", zeroVector)
			.param("id", questionId)
			.update();
	}

	private JsonNode readPayload(AiJobOutbox row) {
		try {
			return objectMapper.readTree(row.getPayload());
		}
		catch (Exception exception) {
			throw new IllegalStateException("Failed to parse outbox payload: " + row.getPayload(), exception);
		}
	}

	private List<AiJobOutbox> outboxRowsFor(Long questionId) {
		return outboxRepository.findAll().stream()
			.filter(row -> row.getJobKey().equals(questionId))
			.toList();
	}

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	private long countWhere(String table, String column, Object value) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :value")
			.param("value", value)
			.query(Long.class)
			.single();
	}
}
