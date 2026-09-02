package shinhan.fibri.ieum.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import shinhan.fibri.ieum.ai.job.dlq.AiJobDeadLetterPublisher;
import shinhan.fibri.ieum.ai.job.dlq.RabbitAiJobDeadLetterPublisher;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchService;
import shinhan.fibri.ieum.testsupport.AiJobQueuePurger;
import shinhan.fibri.ieum.testsupport.AiJobRabbitContainer;
import shinhan.fibri.ieum.testsupport.AiJobRawQueueDrainer;

/**
 * 파싱 불가 메시지 등 "재시도가 고칠 수 없는" 실패는 retry 큐를 거치지 않고 <b>즉시</b> DLQ 로 가야
 * 한다는 spec.md §6.3 규칙을, 실제 Testcontainers RabbitMQ 위에서 끝단으로 검증한다.
 * 브리프 "먼저 쓸 테스트" {@code AiJobImmediateDlqIntegrationTest}.
 *
 * <p>{@link RabbitAiJobDeadLetterPublisher#deadLetter}는 {@code x-death} 카운트를 전혀 보지
 * 않는다 — 이 테스트는 그 사실을 증명한다: 메시지를 <b>단 한 번도 NACK 하지 않고</b> 곧바로 DLQ 로
 * republish + ACK 하므로, retry 큐(TTL 30초)는 단 한 순간도 이 메시지를 보유하지 않는다.
 *
 * <p><b>RED 증거</b>: 브리프의 Task 5 임시 구현({@code TransientNackAiJobDeadLetterPublisher} —
 * {@code deadLetter}가 실제로는 {@code basicNack(tag,false,false)}만 하던 시절)에 대해 이 테스트를
 * 돌리면 poison 메시지가 DLQ에 전혀 도착하지 않아 {@code dlqLatch.await(5, SECONDS)}가 타임아웃으로
 * {@code false}를 반환해 실패한다 — 그 구현이 어떤 카운트도 보지 않고 무조건 NACK 했기 때문이다.
 */
@EnabledIf(value = "shinhan.fibri.ieum.testsupport.DockerAvailability#isAvailable", disabledReason = "Docker unavailable")
class AiJobImmediateDlqIntegrationTest {

	// Dedicated test-only names — NOT the production AiJobTopology queue names. The static
	// singleton broker (AiJobRabbitContainer) is shared by every app-ai test class in the JVM, and
	// other classes (AiJobRabbitTopologyTest, QuestionAnswerDispatchEndToEndTest) already declare
	// the real queue names with the real 30s TTL; redeclaring them here would 406
	// PRECONDITION_FAILED. The retry mechanism itself doesn't depend on the queue name, only on
	// the argument wiring, so an isolated name set is equally valid and collision-free.
	private static final String WORK_QUEUE = "ieum.ai.test.immediate-dlq.dispatch";
	private static final String RETRY_QUEUE = WORK_QUEUE + ".retry";
	private static final String DLQ = WORK_QUEUE + ".dlq";
	private static final String ROUTING_KEY = "ai.test.immediate-dlq.dispatch";
	private static final int RETRY_TTL_MS_FOR_TEST = 1_000;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private CachingConnectionFactory connectionFactory;
	private RabbitAdmin admin;
	private AiJobRawQueueDrainer workDrainer;
	private AiJobRawQueueDrainer dlqDrainer;

	@BeforeEach
	void setUp() {
		connectionFactory = new CachingConnectionFactory(AiJobRabbitContainer.host(), AiJobRabbitContainer.amqpPort());
		connectionFactory.setUsername(AiJobRabbitContainer.username());
		connectionFactory.setPassword(AiJobRabbitContainer.password());
		admin = new RabbitAdmin(connectionFactory);
		AiJobDispatchQueueTopology.declare(admin, RETRY_TTL_MS_FOR_TEST, WORK_QUEUE, RETRY_QUEUE, DLQ, ROUTING_KEY);
		AiJobQueuePurger.purge(admin, WORK_QUEUE, RETRY_QUEUE, DLQ);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (workDrainer != null) {
			workDrainer.stop();
		}
		if (dlqDrainer != null) {
			dlqDrainer.stop();
		}
		connectionFactory.destroy();
	}

	@Test
	void unparseablePayloadReachesTheDlqWithoutEverEnteringTheRetryQueue() throws Exception {
		QuestionAnswerJobDispatchService dispatchService = mock(QuestionAnswerJobDispatchService.class);
		AiJobDeadLetterPublisher deadLetterPublisher = new RabbitAiJobDeadLetterPublisher();
		QuestionAnswerJobMessageListener listener =
			new QuestionAnswerJobMessageListener(dispatchService, deadLetterPublisher, objectMapper);

		AtomicInteger workDeliveries = new AtomicInteger();
		CountDownLatch dlqLatch = new CountDownLatch(1);
		List<Map<String, Object>> dlqHeaders = new CopyOnWriteArrayList<>();

		workDrainer = AiJobRawQueueDrainer.consumingWith(WORK_QUEUE, (channel, delivery) -> {
			workDeliveries.incrementAndGet();
			Message message = toSpringMessage(delivery.getBody(), delivery.getProperties().getHeaders(), WORK_QUEUE);
			listener.onMessage(message, channel, delivery.getEnvelope().getDeliveryTag());
		});
		// Manual-ack and deliberately never ack: the message must stay counted in the DLQ
		// (RabbitMQ's queue "messages" count = ready + unacknowledged) so the message-count
		// assertion below is meaningful instead of racing an active auto-ack consumer that would
		// have already removed it. The @BeforeEach purge on the next test requeues it away.
		dlqDrainer = AiJobRawQueueDrainer.consumingWith(DLQ, (channel, delivery) -> {
			dlqHeaders.add(delivery.getProperties().getHeaders());
			dlqLatch.countDown();
		});

		publishRaw(WORK_QUEUE, "not json at all {{{".getBytes(StandardCharsets.UTF_8));

		assertThat(dlqLatch.await(5, TimeUnit.SECONDS))
			.as("poison message must reach the DLQ immediately, without waiting for any retry TTL")
			.isTrue();

		// give any (incorrect) retry-cycle behaviour a moment to show up before asserting it never does
		Thread.sleep(300);

		// The DLQ message is still unacked (deliberately) on dlqDrainer's channel, so RabbitMQ's
		// passive-declare message count (which reports only "ready" messages) would read 0 while
		// that consumer holds it. Closing the channel makes the broker requeue the unacked message,
		// turning it back into a "ready" message so the count below is meaningful.
		dlqDrainer.stop();
		dlqDrainer = null;
		Thread.sleep(200);

		assertThat(workDeliveries.get()).as("exactly one delivery — no retry cycling").isEqualTo(1);
		assertThat(queueMessageCount(RETRY_QUEUE)).as("retry queue must never be touched").isZero();
		assertThat(queueMessageCount(WORK_QUEUE)).isZero();
		assertThat(queueMessageCount(DLQ)).isEqualTo(1);
		assertThat(dlqHeaders).hasSize(1);
		// raw rabbitmq-client decodes AMQP header string values as LongString, not java.lang.String —
		// compare via toString() rather than isEqualTo(String) reference/type equality.
		assertThat(String.valueOf(dlqHeaders.get(0).get(AiJobDeadLetterPublisher.HEADER_DLQ_REASON)))
			.isEqualTo(AiJobMessageSettlement.REASON_UNPARSEABLE_PAYLOAD);
		verifyNoInteractions(dispatchService);
	}

	private long queueMessageCount(String queue) {
		var info = admin.getQueueInfo(queue);
		return info == null ? -1 : info.getMessageCount();
	}

	private void publishRaw(String queue, byte[] body) throws Exception {
		ConnectionFactory rawFactory = new ConnectionFactory();
		rawFactory.setHost(AiJobRabbitContainer.host());
		rawFactory.setPort(AiJobRabbitContainer.amqpPort());
		rawFactory.setUsername(AiJobRabbitContainer.username());
		rawFactory.setPassword(AiJobRabbitContainer.password());
		try (Connection connection = rawFactory.newConnection(); Channel channel = connection.createChannel()) {
			channel.basicPublish("", queue, null, body);
		}
	}

	private static Message toSpringMessage(byte[] body, Map<String, Object> rawHeaders, String consumerQueue) {
		MessageProperties properties = new MessageProperties();
		properties.setConsumerQueue(consumerQueue);
		if (rawHeaders != null) {
			properties.setHeaders(new HashMap<>(rawHeaders));
		}
		return new Message(body, properties);
	}
}
