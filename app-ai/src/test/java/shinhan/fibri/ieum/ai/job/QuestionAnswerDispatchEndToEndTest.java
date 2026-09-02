package shinhan.fibri.ieum.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.ai.job.dlq.AiJobDeadLetterPublisher;
import shinhan.fibri.ieum.ai.question.repository.JdbcQuestionTaskWorkRepository;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchService;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerOrchestrator;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerTaskLane;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerTaskProcessor;
import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.common.ai.job.AiJobType;
import shinhan.fibri.ieum.common.ai.job.dto.QuestionAnswerDispatchMessage;
import shinhan.fibri.ieum.testsupport.AiJobRabbitContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

/**
 * outbox row (여기서는 직접 발행으로 시뮬레이션) → 릴레이 발행 → app-ai 실제 소비 → 티켓 상태에 따른
 * 정산까지, 실제 Postgres + 실제 RabbitMQ 위에서 끝단(end-to-end)으로 검증한다. 브리프
 * "먼저 쓸 테스트" / spec.md §14.3 {@code QuestionAnswerDispatchEndToEndTest}.
 *
 * <p>app-main 의 {@code AiJobOutboxWriter}/{@code AiJobOutboxRelay}는 이 모듈(app-ai)의 컴파일
 * 의존성 밖이므로, "outbox → 릴레이 발행"은 실제 {@link RabbitTemplate}으로 같은 스키마의
 * {@link QuestionAnswerDispatchMessage}를 큐에 직접 발행하는 것으로 대신한다 — 와이어 위에서
 * app-ai 가 보는 것은 어차피 바이트뿐이라 이 시뮬레이션과 실제 relay 발행은 컨슈머 입장에서
 * 구별할 수 없다.
 *
 * <p><b>핵심 검증(멱등성):</b> 같은 {@code questionId}에 대해 서로 다른 {@code jobId}를 가진
 * 메시지 2건을 연달아 넣어도, 실제 작업 실행({@code QuestionAnswerOrchestrator.process})은
 * 정확히 1회만 일어난다. 두 번째 메시지는 첫 메시지가 이미 DB 티켓을 claim 해 둔
 * ({@code status='processing'}, {@code lease_until}이 미래) 상태를 {@code findDispatchSnapshot()}이
 * 그대로 읽어 {@code activeLease()=true}로 흡수한다(spec.md §8.2 "activeLease() 흡수") — lane 의
 * 인메모리 dedup 이 아니라 DB 상태 머신이 멱등성의 권위임을 이 테스트가 증명한다.
 */
@EnabledIf(value = "shinhan.fibri.ieum.testsupport.DockerAvailability#isAvailable", disabledReason = "Docker unavailable")
class QuestionAnswerDispatchEndToEndTest {

	private static final String DATABASE = "ieum_ai_dispatch_e2e";
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private JdbcClient jdbc;
	private CachingConnectionFactory connectionFactory;
	private ExecutorService laneExecutor;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"))
			.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)")
			.update();
	}

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		DataSource dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);

		connectionFactory = new CachingConnectionFactory(AiJobRabbitContainer.host(), AiJobRabbitContainer.amqpPort());
		connectionFactory.setUsername(AiJobRabbitContainer.username());
		connectionFactory.setPassword(AiJobRabbitContainer.password());
	}

	@AfterEach
	void tearDown() {
		if (laneExecutor != null) {
			laneExecutor.shutdownNow();
		}
		connectionFactory.destroy();
	}

	@Test
	void duplicateDispatchMessagesForTheSameQuestionSubmitToTheLaneExactlyOnce() throws Exception {
		long questionId = insertPendingQuestionTask();

		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		declareTopology(admin);
		// AiJobRabbitContainer 는 JVM 안의 모든 테스트 클래스가 공유하는 정적 싱글턴 브로커다
		// (spec.md 의 별도 vhost 격리와 달리 테스트에서는 기본 vhost 하나뿐). AiJobRabbitTopologyTest 의
		// mandatory 발행 라우팅 체크(routable())가 실제 소비자 없이 이 큐에 프로브 메시지를 남겨둘 수
		// 있으므로, 이 테스트가 보는 첫 메시지가 반드시 우리가 방금 발행한 것이 되도록 선제 purge 한다.
		admin.purgeQueue(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH, false);

		QuestionTaskWorkRepository repository = new JdbcQuestionTaskWorkRepository(jdbc);
		AtomicInteger orchestratorInvocations = new AtomicInteger();
		CountDownLatch firstClaimStarted = new CountDownLatch(1);
		QuestionAnswerOrchestrator orchestrator = claim -> {
			orchestratorInvocations.incrementAndGet();
			firstClaimStarted.countDown();
			// 의도적으로 아무것도 하지 않는다: DB 행이 status='processing' + lease_until(미래)로
			// 남아 있어야 두 번째 메시지가 activeLease() 로 흡수되는 창이 생긴다.
		};
		laneExecutor = Executors.newSingleThreadExecutor();
		QuestionAnswerTaskProcessor processor = new QuestionAnswerTaskProcessor(
			repository, orchestrator, "e2e-worker", Duration.ofMinutes(2), 5
		);
		QuestionAnswerTaskLane lane = new QuestionAnswerTaskLane(true, laneExecutor, processor::process);
		QuestionCompletionCallbackWake callbackWake = ignoredQuestionId -> { };
		QuestionAnswerJobDispatchService dispatchService =
			new QuestionAnswerJobDispatchService(repository, lane, callbackWake);
		AiJobDeadLetterPublisher deadLetterPublisher = mock(AiJobDeadLetterPublisher.class);
		QuestionAnswerJobMessageListener listener =
			new QuestionAnswerJobMessageListener(dispatchService, deadLetterPublisher, OBJECT_MAPPER);

		SimpleQueueDrainer drainer = new SimpleQueueDrainer(connectionFactory, listener);
		drainer.start(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH);

		RabbitTemplate template = new RabbitTemplate(connectionFactory);
		template.send(
			AiJobTopology.EXCHANGE_JOBS,
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH,
			dispatchMessage(questionId, "created")
		);

		assertThat(firstClaimStarted.await(10, TimeUnit.SECONDS))
			.as("first message's async lane execution must actually claim the DB row")
			.isTrue();

		template.send(
			AiJobTopology.EXCHANGE_JOBS,
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH,
			dispatchMessage(questionId, "regenerated")
		);

		awaitUntil(Duration.ofSeconds(10), () -> drainer.processedCount() >= 2);
		drainer.stop();

		assertThat(orchestratorInvocations.get())
			.as("lane submission must happen exactly once despite two messages for the same questionId")
			.isEqualTo(1);
		assertThat(taskStatus(questionId)).isEqualTo("processing");
		verifyNoInteractions(deadLetterPublisher);
	}

	private Message dispatchMessage(long questionId, String reason) throws Exception {
		QuestionAnswerDispatchMessage payload = new QuestionAnswerDispatchMessage(
			AiJobTopology.SCHEMA_VERSION,
			UUID.randomUUID().toString(),
			AiJobType.QUESTION_ANSWER_DISPATCH,
			questionId,
			reason,
			OffsetDateTime.now().toString()
		);
		byte[] body = OBJECT_MAPPER.writeValueAsBytes(payload);
		MessageProperties properties = new MessageProperties();
		properties.setContentType("application/json");
		return new Message(body, properties);
	}

	private void declareTopology(RabbitAdmin admin) {
		admin.declareExchange(org.springframework.amqp.core.ExchangeBuilder
			.directExchange(AiJobTopology.EXCHANGE_JOBS).durable(true).build());
		admin.declareExchange(org.springframework.amqp.core.ExchangeBuilder
			.directExchange(AiJobTopology.EXCHANGE_RETRY).durable(true).build());
		admin.declareExchange(org.springframework.amqp.core.ExchangeBuilder
			.directExchange(AiJobTopology.EXCHANGE_DLX).durable(true).build());

		java.util.Map<String, Object> workArgs = java.util.Map.of(
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_RETRY,
			"x-dead-letter-routing-key", AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY
		);
		admin.declareQueue(org.springframework.amqp.core.QueueBuilder
			.durable(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH).withArguments(workArgs).build());
		admin.declareBinding(org.springframework.amqp.core.BindingBuilder
			.bind(new org.springframework.amqp.core.Queue(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH))
			.to(new org.springframework.amqp.core.DirectExchange(AiJobTopology.EXCHANGE_JOBS))
			.with(AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH));

		java.util.Map<String, Object> retryArgs = java.util.Map.of(
			"x-message-ttl", AiJobTopology.RETRY_TTL_MS,
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_JOBS,
			"x-dead-letter-routing-key", AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH
		);
		admin.declareQueue(org.springframework.amqp.core.QueueBuilder
			.durable(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY).withArguments(retryArgs).build());
		admin.declareBinding(org.springframework.amqp.core.BindingBuilder
			.bind(new org.springframework.amqp.core.Queue(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY))
			.to(new org.springframework.amqp.core.DirectExchange(AiJobTopology.EXCHANGE_RETRY))
			.with(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY));

		admin.declareQueue(org.springframework.amqp.core.QueueBuilder
			.durable(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ).build());
		admin.declareBinding(org.springframework.amqp.core.BindingBuilder
			.bind(new org.springframework.amqp.core.Queue(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ))
			.to(new org.springframework.amqp.core.DirectExchange(AiJobTopology.EXCHANGE_DLX))
			.with(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ));
	}

	private long insertPendingQuestionTask() {
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES ('e2e-dispatch@example.com', 'hash', 'e2e-dispatch', true)
			RETURNING user_id
			""")
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
			RETURNING pin_id
			""")
			.param("userId", userId)
			.query(Long.class)
			.single();
		long questionId = jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, :userId, 'e2e question title', 'e2e question content')
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.query(Long.class)
			.single();
		jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id, attempts, next_attempt_at, created_at)
			VALUES (:questionId, 0, now(), now())
			""")
			.param("questionId", questionId)
			.update();
		return questionId;
	}

	private String taskStatus(long questionId) {
		return jdbc.sql("SELECT status::text FROM ai_question_tasks WHERE question_id = :questionId")
			.param("questionId", questionId)
			.query(String.class)
			.single();
	}

	private static void awaitUntil(Duration timeout, java.util.function.BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("condition not met within " + timeout);
	}

	/**
	 * 테스트 전용 최소 컨슈머 루프. 실제 {@code @RabbitListener} 컨테이너 배선(Spring Boot 자동설정)
	 * 없이도, manual ACK 계약(spec.md §6.4)을 그대로 지키며 {@link QuestionAnswerJobMessageListener}를
	 * 실제 브로커 채널로 구동한다 — {@code basicConsume} 콜백에서 리스너를 그대로 호출한다.
	 */
	private static final class SimpleQueueDrainer {
		private final CachingConnectionFactory connectionFactory;
		private final QuestionAnswerJobMessageListener listener;
		private final AtomicInteger processed = new AtomicInteger();
		private com.rabbitmq.client.Connection connection;
		private com.rabbitmq.client.Channel channel;

		SimpleQueueDrainer(CachingConnectionFactory connectionFactory, QuestionAnswerJobMessageListener listener) {
			this.connectionFactory = connectionFactory;
			this.listener = listener;
		}

		void start(String queue) throws Exception {
			com.rabbitmq.client.ConnectionFactory rawFactory = new com.rabbitmq.client.ConnectionFactory();
			rawFactory.setHost(connectionFactory.getHost());
			rawFactory.setPort(connectionFactory.getPort());
			rawFactory.setUsername(AiJobRabbitContainer.username());
			rawFactory.setPassword(AiJobRabbitContainer.password());
			connection = rawFactory.newConnection();
			channel = connection.createChannel();
			channel.basicQos(AiJobTopology.PREFETCH);
			channel.basicConsume(queue, false, (consumerTag, delivery) -> {
				Message message = new Message(delivery.getBody(), new MessageProperties());
				try {
					listener.onMessage(message, channel, delivery.getEnvelope().getDeliveryTag());
				}
				finally {
					processed.incrementAndGet();
				}
			}, consumerTag -> { });
		}

		int processedCount() {
			return processed.get();
		}

		void stop() throws Exception {
			if (channel != null) {
				channel.close();
			}
			if (connection != null) {
				connection.close();
			}
		}
	}
}
