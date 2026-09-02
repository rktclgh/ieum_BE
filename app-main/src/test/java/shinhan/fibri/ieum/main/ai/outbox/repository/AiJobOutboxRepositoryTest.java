package shinhan.fibri.ieum.main.ai.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

/**
 * {@code AiJobOutbox} <-> {@code ai_job_outbox} 저장·조회 round-trip. spec.md §5.2, Task 3 브리프.
 * 스키마는 canonical {@code db/schema.sql}로 미리 적용되므로 {@code ddl-auto=none}이다
 * (검증 자체는 {@link AiJobOutboxSchemaValidateTest}가 담당).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiJobOutboxRepositoryTest {

	private static final String DATABASE = "ieum_ai_job_outbox_repository";

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private AiJobOutboxRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@PersistenceContext
	private EntityManager entityManager;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE ai_job_outbox RESTART IDENTITY CASCADE");
	}

	@Test
	void savesAndReloadsPendingRowWithJsonbPayload() throws Exception {
		UUID jobId = UUID.randomUUID();
		String payloadJson = """
			{"schemaVersion":1,"jobId":"%s","jobType":"question_answer_dispatch","questionId":42,"reason":"created","occurredAt":"2026-09-02T09:15:04.512Z"}
			""".formatted(jobId).strip();

		AiJobOutbox saved = repository.saveAndFlush(AiJobOutbox.pending(
			jobId, "question_answer_dispatch", 42L, "ai.question-answer.dispatch", payloadJson
		));
		entityManager.clear();

		AiJobOutbox reloaded = repository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getJobId()).isEqualTo(jobId);
		assertThat(reloaded.getJobType()).isEqualTo("question_answer_dispatch");
		assertThat(reloaded.getJobKey()).isEqualTo(42L);
		assertThat(reloaded.getRoutingKey()).isEqualTo("ai.question-answer.dispatch");
		assertThat(reloaded.getSchemaVersion()).isEqualTo((short) 1);
		assertThat(reloaded.getStatus()).isEqualTo("pending");
		assertThat(reloaded.getAttempts()).isEqualTo((short) 0);
		assertThat(reloaded.getNextAttemptAt()).isNotNull();
		assertThat(reloaded.getCreatedAt()).isNotNull();
		assertThat(reloaded.getUpdatedAt()).isNotNull();
		assertThat(reloaded.getPublishedAt()).isNull();
		assertThat(reloaded.getLeaseToken()).isNull();

		JsonNode expected = objectMapper.readTree(payloadJson);
		JsonNode actual = objectMapper.readTree(reloaded.getPayload());
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	void payloadIsStoredAsRealJsonbNotOpaqueText() {
		UUID jobId = UUID.randomUUID();
		AiJobOutbox saved = repository.saveAndFlush(AiJobOutbox.pending(
			jobId, "accepted_answer_knowledge_ingest", 7L, "ai.accepted-answer.ingest",
			"{\"answerId\":7,\"jobId\":\"%s\"}".formatted(jobId)
		));

		String questionIdFromJsonb = jdbc.queryForObject(
			"SELECT payload ->> 'answerId' FROM ai_job_outbox WHERE outbox_id = ?",
			String.class,
			saved.getId()
		);
		assertThat(questionIdFromJsonb).isEqualTo("7");

		String pgTypeOfPayload = jdbc.queryForObject(
			"SELECT pg_typeof(payload)::text FROM ai_job_outbox WHERE outbox_id = ?",
			String.class,
			saved.getId()
		);
		assertThat(pgTypeOfPayload).isEqualTo("jsonb");
	}

	@Test
	void duplicateJobIdViolatesUniqueConstraint() {
		UUID jobId = UUID.randomUUID();
		repository.saveAndFlush(AiJobOutbox.pending(
			jobId, "question_answer_dispatch", 1L, "ai.question-answer.dispatch", "{\"questionId\":1}"
		));

		assertThatThrownBy(() -> repository.saveAndFlush(AiJobOutbox.pending(
			jobId, "question_answer_dispatch", 2L, "ai.question-answer.dispatch", "{\"questionId\":2}"
		))).isInstanceOf(DataIntegrityViolationException.class);
	}
}
