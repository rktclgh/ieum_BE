package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.testsupport.AiJobRabbitContainer;
import shinhan.fibri.ieum.testsupport.DockerAvailability;

/**
 * app-main 이 선언하는 AI job 토폴로지가 실제 브로커에 그대로 생기는지, 그리고 같은 선언을 반복해도
 * {@code PRECONDITION_FAILED} 없이 멱등한지 검증한다. spec.md §6.2, Task 4 브리프.
 *
 * <p>큐 인자 일치는 브로커의 equivalence 검사로 증명한다 — 이미 존재하는 큐를 <b>같은 인자</b>로
 * 다시 선언하면 성공하고, <b>다른 인자</b>로 선언하면 406 PRECONDITION_FAILED 로 채널이 죽는다.
 * 그래서 "선언된 인자 == {@code AiJobTopology} 상수"가 된다.
 */
@EnabledIf(value = "shinhan.fibri.ieum.testsupport.DockerAvailability#isAvailable", disabledReason = "Docker unavailable")
class AiJobRabbitTopologyTest {

	private static final List<String> ALL_QUEUES = List.of(
		AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH,
		AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY,
		AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ,
		AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST,
		AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY,
		AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_DLQ,
		AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED,
		AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY,
		AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ
	);

	private static final List<String> ALL_EXCHANGES = List.of(
		AiJobTopology.EXCHANGE_JOBS,
		AiJobTopology.EXCHANGE_RESULTS,
		AiJobTopology.EXCHANGE_RETRY,
		AiJobTopology.EXCHANGE_DLX
	);

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withInitializer(bootConversionService())
			.withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
			.withUserConfiguration(AiJobRabbitConfig.class)
			.withPropertyValues(
				"app.ai.outbox.enabled=true",
				"spring.rabbitmq.host=" + AiJobRabbitContainer.host(),
				"spring.rabbitmq.port=" + AiJobRabbitContainer.amqpPort(),
				"spring.rabbitmq.username=" + AiJobRabbitContainer.username(),
				"spring.rabbitmq.password=" + AiJobRabbitContainer.password(),
				"spring.rabbitmq.publisher-confirm-type=correlated",
				"spring.rabbitmq.publisher-returns=true",
				"spring.rabbitmq.template.mandatory=true"
			);
	}

	@Test
	void declaresEveryExchangeQueueAndBinding() {
		runner().run(context -> {
			RabbitAdmin admin = context.getBean(RabbitAdmin.class);
			admin.initialize();

			assertThat(context.getBeansOfType(Queue.class)).hasSize(ALL_QUEUES.size());
			for (String queue : ALL_QUEUES) {
				assertThat(admin.getQueueInfo(queue)).as("queue %s", queue).isNotNull();
			}
			try (Connection connection = rawConnection(); Channel channel = connection.createChannel()) {
				for (String exchange : ALL_EXCHANGES) {
					channel.exchangeDeclarePassive(exchange);
				}
			}
			// routable() 이 실제로 남기는 "{}" 프로브를, 검증이 끝나면(실패해도) 반드시 치운다 —
			// 이 클래스와 AiJobPublisherConfirmIntegrationTest 가 공유하는 static 브로커에 프로브가
			// 남으면 다른 테스트의 receive()/getMessageCount() 가 그걸 집어 든다.
			try {
				// 바인딩 확인: jobs exchange 로 mandatory 발행이 반송되지 않아야 한다.
				assertThat(routable(AiJobTopology.EXCHANGE_JOBS, AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH))
					.isTrue();
				assertThat(routable(AiJobTopology.EXCHANGE_JOBS, AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST))
					.isTrue();
				assertThat(routable(
					AiJobTopology.EXCHANGE_RESULTS, AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED
				)).isTrue();
				assertThat(routable(
					AiJobTopology.EXCHANGE_RETRY, AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY
				)).isTrue();
				assertThat(routable(AiJobTopology.EXCHANGE_DLX, AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ))
					.isTrue();
			}
			finally {
				purgeProbeMessages(admin);
			}
			// 정리가 실제로 효과가 있었는지: 다른 테스트가 이 static 브로커를 이어받기 전에
			// 프로브가 하나도 안 남아 있어야 한다.
			for (String queue : ALL_QUEUES) {
				assertThat(admin.getQueueInfo(queue).getMessageCount()).as("queue %s", queue).isZero();
			}
		});
	}

	@Test
	void redeclaringTheWholeTopologyIsIdempotent() {
		runner().run(context -> {
			RabbitAdmin admin = context.getBean(RabbitAdmin.class);
			admin.initialize();
			admin.initialize();
		});
		// 새 컨텍스트(=새 연결)에서 다시 선언해도 PRECONDITION_FAILED 가 없어야 한다.
		runner().run(context -> {
			RabbitAdmin admin = context.getBean(RabbitAdmin.class);
			admin.initialize();
			for (String queue : ALL_QUEUES) {
				assertThat(admin.getQueueInfo(queue)).as("queue %s", queue).isNotNull();
			}
		});
	}

	@Test
	void declaredQueueArgumentsMatchTopologyConstants() throws Exception {
		runner().run(context -> context.getBean(RabbitAdmin.class).initialize());

		for (Map.Entry<String, Map<String, Object>> expected : expectedQueueArguments().entrySet()) {
			try (Connection connection = rawConnection(); Channel channel = connection.createChannel()) {
				channel.queueDeclare(expected.getKey(), true, false, false, expected.getValue());
			}
		}
	}

	@Test
	void mismatchedQueueArgumentsAreRejectedByTheBroker() {
		runner().run(context -> context.getBean(RabbitAdmin.class).initialize());

		Map<String, Object> wrong = new HashMap<>(
			expectedQueueArguments().get(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY)
		);
		wrong.put("x-message-ttl", AiJobTopology.RETRY_TTL_MS + 1);

		assertThatThrownBy(() -> {
			try (Connection connection = rawConnection(); Channel channel = connection.createChannel()) {
				channel.queueDeclare(
					AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY, true, false, false, wrong
				);
			}
		}).isInstanceOf(IOException.class);
	}

	@Test
	void topologyIsNotDeclaredWhileTheOutboxRelayIsDisabled() {
		new ApplicationContextRunner()
			.withInitializer(bootConversionService())
			.withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
			.withUserConfiguration(AiJobRabbitConfig.class)
			.run(context -> assertThat(context.getBeansOfType(Queue.class)).isEmpty());
	}

	private static Map<String, Map<String, Object>> expectedQueueArguments() {
		Map<String, Map<String, Object>> expected = new HashMap<>();
		expected.put(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH, Map.of(
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_RETRY,
			"x-dead-letter-routing-key", AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY
		));
		expected.put(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY, Map.of(
			"x-message-ttl", AiJobTopology.RETRY_TTL_MS,
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_JOBS,
			"x-dead-letter-routing-key", AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH
		));
		expected.put(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ, Map.of());
		expected.put(AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST, Map.of(
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_RETRY,
			"x-dead-letter-routing-key", AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY
		));
		expected.put(AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY, Map.of(
			"x-message-ttl", AiJobTopology.RETRY_TTL_MS,
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_JOBS,
			"x-dead-letter-routing-key", AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST
		));
		expected.put(AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_DLQ, Map.of());
		expected.put(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED, Map.of(
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_RETRY,
			"x-dead-letter-routing-key", AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY
		));
		expected.put(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY, Map.of(
			"x-message-ttl", AiJobTopology.RETRY_TTL_MS,
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_RESULTS,
			"x-dead-letter-routing-key", AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED
		));
		expected.put(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ, Map.of());
		return expected;
	}

	/**
	 * {@code routable()} 프로브가 작업 큐에 남긴 뒤 retry TTL 만료로 dead-letter 돼 되돌아올 수도 있는
	 * work/retry/DLQ 큐를 전부 비운다. work queue 로 라우팅된 프로브는 소비되지 않으면 그대로 남고,
	 * retry queue 로 직접 발행된 프로브는 TTL 이 지나면 원래 work queue 로 dead-letter 된다 — 그래서
	 * {@code ALL_QUEUES} 전체를 비워야 어느 경로로도 다른 테스트에 새지 않는다.
	 */
	private static void purgeProbeMessages(RabbitAdmin admin) {
		for (String queue : ALL_QUEUES) {
			admin.purgeQueue(queue, false);
		}
	}

	/** mandatory 발행이 반송되지 않으면 해당 exchange/routing key 에 바인딩이 있다는 뜻이다. */
	private static boolean routable(String exchange, String routingKey) throws Exception {
		try (Connection connection = rawConnection(); Channel channel = connection.createChannel()) {
			java.util.concurrent.atomic.AtomicBoolean returned = new java.util.concurrent.atomic.AtomicBoolean();
			channel.confirmSelect();
			channel.addReturnListener(ret -> returned.set(true));
			channel.basicPublish(exchange, routingKey, true, null, "{}".getBytes());
			channel.waitForConfirmsOrDie(5_000);
			return !returned.get();
		}
	}

	private static Connection rawConnection() throws Exception {
		ConnectionFactory factory = new ConnectionFactory();
		factory.setHost(AiJobRabbitContainer.host());
		factory.setPort(AiJobRabbitContainer.amqpPort());
		factory.setUsername(AiJobRabbitContainer.username());
		factory.setPassword(AiJobRabbitContainer.password());
		return factory.newConnection();
	}

	/**
	 * {@code ApplicationContextRunner}는 맨몸 컨텍스트라 Boot 가 실제 앱에 넣어주는
	 * {@code String -> Duration} 변환기가 없다. 실제 기동과 같은 조건으로 맞춘다.
	 */
	private static org.springframework.context.ApplicationContextInitializer<
		org.springframework.context.ConfigurableApplicationContext> bootConversionService() {
		return context -> context.getBeanFactory()
			.setConversionService(ApplicationConversionService.getSharedInstance());
	}

}
