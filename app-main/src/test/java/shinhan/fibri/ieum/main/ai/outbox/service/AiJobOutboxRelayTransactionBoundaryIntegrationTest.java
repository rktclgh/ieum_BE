package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

/**
 * spec.md §7.3 의 핵심 불변식: <b>브로커 왕복 동안 DB 트랜잭션이나 row lock 을 잡지 않는다.</b>
 *
 * <p>모킹된 {@code RabbitTemplate.send(...)} 안에서 두 가지를 동시에 확인한다.
 * <ol>
 *   <li>스레드에 활성 트랜잭션이 없다 — 클레임 TX 가 이미 커밋됐다는 뜻.</li>
 *   <li>같은 시점에 <b>독립 커넥션</b>으로 읽은 row 가 이미 {@code publishing} 이다 —
 *       클레임이 커밋되지 않았다면 다른 커넥션에서 보이지 않는다.</li>
 * </ol>
 * 브로커 호출이 클레임 TX 안에서 일어나면 두 단언 모두 깨진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiJobOutboxRelayTransactionBoundaryIntegrationTest {

	private static final String DATABASE = "ieum_ai_job_outbox_relay_tx_boundary";

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private AiJobOutboxRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	private RabbitTemplate rabbitTemplate;
	private AiJobOutboxRelay relay;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE ai_job_outbox RESTART IDENTITY CASCADE");
		rabbitTemplate = mock(RabbitTemplate.class);
		relay = new AiJobOutboxRelay(
			repository,
			rabbitTemplate,
			new AiJobOutboxProperties("worker-tx", Duration.ofSeconds(60), 8, 32, Duration.ofSeconds(5), 7, 100)
		);
	}

	private long insertPending() {
		UUID jobId = UUID.randomUUID();
		return repository.saveAndFlush(AiJobOutbox.pending(
			jobId,
			"question_answer_dispatch",
			42L,
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH,
			AiJobTopology.SCHEMA_VERSION,
			"{\"schemaVersion\":1,\"jobId\":\"%s\",\"questionId\":42}".formatted(jobId)
		)).getId();
	}

	@Test
	void brokerRoundTripHappensAfterTheClaimTransactionCommits() {
		long outboxId = insertPending();
		AtomicReference<Boolean> transactionActiveDuringSend = new AtomicReference<>();
		AtomicReference<String> statusSeenByAnotherConnection = new AtomicReference<>();

		doAnswer(invocation -> {
			transactionActiveDuringSend.set(TransactionSynchronizationManager.isActualTransactionActive());
			statusSeenByAnotherConnection.set(jdbc.queryForObject(
				"SELECT status FROM ai_job_outbox WHERE outbox_id = ?", String.class, outboxId
			));
			CorrelationData correlation = invocation.getArgument(3);
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate)
			.send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		assertThat(relay.publishBatch()).isEqualTo(1);

		assertThat(transactionActiveDuringSend.get()).isFalse();
		assertThat(statusSeenByAnotherConnection.get()).isEqualTo("publishing");

		Map<String, Object> settled = jdbc.queryForMap(
			"SELECT * FROM ai_job_outbox WHERE outbox_id = ?", outboxId
		);
		assertThat(settled.get("status")).isEqualTo("published");
		assertThat(settled.get("published_at")).isNotNull();
		assertThat(settled.get("lease_token")).isNull();
	}

	@Test
	void nackedPublishLeavesTheRowRetryableWithABackoff() {
		long outboxId = insertPending();
		doAnswer(invocation -> {
			CorrelationData correlation = invocation.getArgument(3);
			correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker down"));
			return null;
		}).when(rabbitTemplate)
			.send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		relay.publishBatch();

		Map<String, Object> settled = jdbc.queryForMap(
			"SELECT * FROM ai_job_outbox WHERE outbox_id = ?", outboxId
		);
		assertThat(settled.get("status")).isEqualTo("retry");
		assertThat(settled.get("last_error_code")).isEqualTo("nack");
		assertThat(((Number) settled.get("attempts")).intValue()).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT next_attempt_at > now() FROM ai_job_outbox WHERE outbox_id = ?", Boolean.class, outboxId
		)).isTrue();
	}
}
