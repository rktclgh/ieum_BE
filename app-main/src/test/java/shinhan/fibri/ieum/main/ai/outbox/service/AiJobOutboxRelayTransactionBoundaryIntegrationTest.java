package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * <p>{@code relay}를 <b>실제 Spring 컨텍스트의 빈</b>으로 얻는다({@code @Import(AiJobOutboxRelay.class)}).
 * {@code new AiJobOutboxRelay(...)}로 직접 생성하면 Spring AOP 프록시가 없어서, 누군가 나중에
 * {@code AiJobOutboxRelay}(운영 코드의 실제 {@code @Service} 빈)에 {@code @Transactional}을 잘못
 * 추가해도 이 테스트가 그 회귀를 잡지 못한다 — 프록시가 없으니
 * {@code TransactionSynchronizationManager.isActualTransactionActive()}가 항상 {@code false}로
 * 남기 때문이다. 빈으로 얻어야만 {@code @Transactional} 뮤테이션이 실제로 프록시돼 이 테스트가
 * 의미를 갖는다({@code AiJobOutboxWriterTransactionBoundaryIntegrationTest}와 같은 패턴).
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
@Import({
	AiJobOutboxRelay.class,
	AiJobOutboxRelayTransactionBoundaryIntegrationTest.RelayPropertiesConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiJobOutboxRelayTransactionBoundaryIntegrationTest {

	@TestConfiguration
	static class RelayPropertiesConfiguration {

		@Bean
		AiJobOutboxProperties aiJobOutboxProperties() {
			return new AiJobOutboxProperties("worker-tx", Duration.ofSeconds(60), 8, 32, Duration.ofSeconds(5), 7, 100);
		}
	}

	private static final String DATABASE = "ieum_ai_job_outbox_relay_tx_boundary";

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		// AiJobOutboxRelay는 @ConditionalOnProperty(app.ai.outbox.enabled=true)다 —
		// @Import로 직접 등록해도 이 조건은 그대로 평가되므로 켜야 빈이 실제로 생긴다.
		registry.add("app.ai.outbox.enabled", () -> "true");
	}

	@Autowired
	private AiJobOutboxRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	/** 실제 컨텍스트 빈 — {@code @Transactional} 뮤테이션이 있으면 이 참조가 프록시다. */
	@Autowired
	private AiJobOutboxRelay relay;

	@MockitoBean
	private RabbitTemplate rabbitTemplate;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE ai_job_outbox RESTART IDENTITY CASCADE");
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
