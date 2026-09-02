package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * app-ai 가 선언하는 AI job 토폴로지가 app-main 의 선언과 <b>양방향 어느 순서로도</b> 충돌하지
 * 않는지 검증한다. spec.md §6.2, 브리프 "먼저 쓸 테스트".
 *
 * <p>app-ai 모듈은 app-main 모듈의 클래스에 컴파일 의존성이 없다(별도 Gradle 모듈, 서로 다른
 * 배포 단위). 따라서 "app-main 이 선언한다"는 사실을 app-main 이 실제로 선언하는 것과 <b>정확히
 * 같은 인자 맵</b>({@link #expectedQueueArguments()} — {@code app-main/.../AiJobRabbitTopologyTest}의
 * 동일 메서드와 값이 같다, 둘 다 {@link AiJobTopology} 상수에서만 값을 가져오므로 드리프트가
 * 구조적으로 불가능하다)으로 raw AMQP 클라이언트가 큐를 (재)선언하는 것으로 시뮬레이션한다.
 * RabbitMQ 의 큐 선언 equivalence 검사는 "누가 먼저 선언했는가"와 무관하게 인자만 비교하므로,
 * 이 방법으로 두 방향(app-main 먼저 / app-ai 먼저) 모두를 증명할 수 있다:
 * <ul>
 *   <li>{@link #rawDeclarationMirroringAppMainSucceedsBeforeAppAiDeclares()} (@Order 1) — "app-main
 *       이 먼저 선언" 시나리오. app-ai 의 Spring 컨텍스트가 뜨기 전에 raw 클라이언트로 전체 토폴로지를
 *       선언한다.</li>
 *   <li>{@link #appAiSpringConfigurationDeclaresWithoutConflict()} (@Order 2) — app-ai 의
 *       {@link AiJobRabbitConfiguration}이 <b>이미 존재하는</b> 큐/exchange 를 같은 인자로 다시
 *       선언해도 {@code PRECONDITION_FAILED} 없이 성공한다.</li>
 *   <li>{@link #rawRedeclareAfterAppAiSpringDeclarationSucceeds()} (@Order 3) — "app-ai 가 먼저
 *       선언된 뒤 app-main 이 다시 선언" 시나리오(반대 방향)를 raw 재선언으로 증명한다.</li>
 * </ul>
 */
@EnabledIf(value = "shinhan.fibri.ieum.testsupport.DockerAvailability#isAvailable", disabledReason = "Docker unavailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
			.withUserConfiguration(AiJobRabbitConfiguration.class)
			.withPropertyValues(
				"app.ai.dispatch.transport=rabbitmq",
				"spring.rabbitmq.host=" + AiJobRabbitContainer.host(),
				"spring.rabbitmq.port=" + AiJobRabbitContainer.amqpPort(),
				"spring.rabbitmq.username=" + AiJobRabbitContainer.username(),
				"spring.rabbitmq.password=" + AiJobRabbitContainer.password()
			);
	}

	@Test
	@Order(1)
	void rawDeclarationMirroringAppMainSucceedsBeforeAppAiDeclares() throws Exception {
		try (Connection connection = rawConnection(); Channel channel = connection.createChannel()) {
			for (String exchange : ALL_EXCHANGES) {
				channel.exchangeDeclare(exchange, "direct", true);
			}
			for (Map.Entry<String, Map<String, Object>> expected : expectedQueueArguments().entrySet()) {
				channel.queueDeclare(expected.getKey(), true, false, false, expected.getValue());
			}
		}
	}

	@Test
	@Order(2)
	void appAiSpringConfigurationDeclaresWithoutConflict() {
		runner().run(context -> {
			RabbitAdmin admin = context.getBean(RabbitAdmin.class);
			admin.initialize();

			assertThat(context.getBeansOfType(Queue.class)).hasSize(ALL_QUEUES.size());
			for (String queue : ALL_QUEUES) {
				assertThat(admin.getQueueInfo(queue)).as("queue %s", queue).isNotNull();
			}
			assertThat(routable(AiJobTopology.EXCHANGE_JOBS, AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH))
				.isTrue();
			assertThat(routable(AiJobTopology.EXCHANGE_JOBS, AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST))
				.isTrue();
			assertThat(routable(AiJobTopology.EXCHANGE_RESULTS, AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED))
				.isTrue();
		});
	}

	@Test
	@Order(3)
	void rawRedeclareAfterAppAiSpringDeclarationSucceeds() throws Exception {
		try (Connection connection = rawConnection(); Channel channel = connection.createChannel()) {
			for (Map.Entry<String, Map<String, Object>> expected : expectedQueueArguments().entrySet()) {
				channel.queueDeclare(expected.getKey(), true, false, false, expected.getValue());
			}
		}
	}

	@Test
	@Order(4)
	void mismatchedQueueArgumentsAreRejectedByTheBroker() {
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
	@Order(5)
	void topologyIsNotDeclaredWhenTransportIsHttp() {
		new ApplicationContextRunner()
			.withInitializer(bootConversionService())
			.withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
			.withUserConfiguration(AiJobRabbitConfiguration.class)
			.withPropertyValues("app.ai.dispatch.transport=http")
			.run(context -> assertThat(context.getBeansOfType(Queue.class)).isEmpty());
	}

	@Test
	@Order(6)
	void topologyIsDeclaredByDefaultWithoutAnExplicitTransportProperty() {
		new ApplicationContextRunner()
			.withInitializer(bootConversionService())
			.withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
			.withUserConfiguration(AiJobRabbitConfiguration.class)
			.run(context -> assertThat(context.getBeansOfType(Queue.class)).hasSize(ALL_QUEUES.size()));
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

	/** mandatory 발행이 반송되지 않으면 해당 exchange/routing key 에 바인딩이 있다는 뜻이다. */
	private static boolean routable(String exchange, String routingKey) {
		try (Connection connection = rawConnection(); Channel channel = connection.createChannel()) {
			java.util.concurrent.atomic.AtomicBoolean returned = new java.util.concurrent.atomic.AtomicBoolean();
			channel.confirmSelect();
			channel.addReturnListener(ret -> returned.set(true));
			channel.basicPublish(exchange, routingKey, true, null, "{}".getBytes());
			channel.waitForConfirmsOrDie(5_000);
			return !returned.get();
		}
		catch (Exception exception) {
			throw new RuntimeException(exception);
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
