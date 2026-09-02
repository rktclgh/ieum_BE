package shinhan.fibri.ieum.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchResult;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchService;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.testsupport.AiJobQueuePurger;
import shinhan.fibri.ieum.testsupport.AiJobRabbitContainer;
import shinhan.fibri.ieum.testsupport.AiJobRawQueueDrainer;

/**
 * TTL + DLX + {@code x-death} 카운팅 재시도 메커니즘을 실제 Testcontainers RabbitMQ 위에서 끝단으로
 * 검증한다. spec.md §6.3, 브리프 "먼저 쓸 테스트" {@code AiJobRetryDlqIntegrationTest}.
 *
 * <p>{@code AiJobTopology.RETRY_TTL_MS}(30초)는 컴파일 타임 상수라 그대로 쓰면 테스트가 5번의 TTL
 * 만료(150초)를 기다려야 한다. {@link AiJobDispatchQueueTopology#declare}로 retry TTL 1초, 격리된
 * 테스트 전용 큐 이름으로 선언한다(다른 테스트 클래스가 공유 브로커에 이미 선언해 둔 실제 큐 이름·TTL
 * 30초와 충돌하지 않도록) — {@code AiJobTopology.MAX_DELIVERY_ATTEMPTS}(=5)와 republish/ACK 로직
 * 자체는 실제 프로덕션 코드({@link RabbitAiJobDeadLetterPublisher})를 그대로 쓴다.
 *
 * <p>컨슈머는 {@code QuestionAnswerJobDispatchService.dispatch(...)}가 항상 {@code SATURATED}를
 * 반환하도록 모킹해 "계속 NACK" 시나리오를 만든다 — 실제 {@link QuestionAnswerJobMessageListener}가
 * 그 판정을 {@link AiJobDeadLetterPublisher#retryOrDeadLetter}에 위임하는 배선(브리프 "구현 단계" 3번)까지
 * 포함해서 검증한다.
 *
 * <p><b>RED 증거</b>: Task 5 임시 구현({@code TransientNackAiJobDeadLetterPublisher})이나, 리스너가
 * {@code SATURATED}를 곧장 {@code channel.basicNack(...)}으로만 처리하던 이전 코드에 대해 이 테스트를
 * 돌리면 메시지가 work↔retry 큐를 영원히 순환할 뿐 DLQ 에 도착하지 않아 {@code dlqLatch.await(20,
 * SECONDS)}가 타임아웃으로 실패한다.
 */
@EnabledIf(value = "shinhan.fibri.ieum.testsupport.DockerAvailability#isAvailable", disabledReason = "Docker unavailable")
class AiJobRetryDlqIntegrationTest {

	private static final int RETRY_TTL_MS_FOR_TEST = 1_000;
	// Dedicated test-only names — see AiJobDispatchQueueTopology's Javadoc for why these must not
	// be the production AiJobTopology queue names (shared static broker + real 30s TTL already
	// declared by other test classes).
	private static final String WORK_QUEUE = "ieum.ai.test.retry-dlq.dispatch";
	private static final String RETRY_QUEUE = WORK_QUEUE + ".retry";
	private static final String DLQ = WORK_QUEUE + ".dlq";
	private static final String ROUTING_KEY = "ai.test.retry-dlq.dispatch";

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
	void messageIsRetriedFiveTimesThenLandsInTheDlqExactlyOnce() throws Exception {
		assertMessageIsRetriedFiveTimesThenLandsInTheDlq(
			777L,
			dispatchService -> when(dispatchService.dispatch(777L)).thenReturn(QuestionAnswerJobDispatchResult.SATURATED),
			AiJobMessageSettlement.REASON_DISPATCH_SATURATED
		);
	}

	/**
	 * 리뷰 발견 사항 보강: {@code dispatchService.dispatch(...)}가 매번 {@code RuntimeException}을
	 * 던지는 경우도 위 SATURATED 시나리오와 동일하게 재시도 상한을 거쳐 DLQ 에 도착해야 한다 — 리스너가
	 * 이 예외를 잡지 않고 그대로 흘려보내면 Spring 의 {@code default-requeue-rejected=false} 처리기가
	 * {@code x-death}를 보지 않고 NACK 하므로 상한에 도달하지 못한 채 work↔retry 큐를 영원히 순환한다.
	 */
	@Test
	void messageWhoseDispatchThrowsIsRetriedFiveTimesThenLandsInTheDlqExactlyOnce() throws Exception {
		assertMessageIsRetriedFiveTimesThenLandsInTheDlq(
			778L,
			dispatchService -> when(dispatchService.dispatch(778L)).thenThrow(new IllegalStateException("boom")),
			AiJobMessageSettlement.REASON_DISPATCH_EXCEPTION
		);
	}

	/**
	 * 위 두 테스트(SATURATED 판정 vs dispatch 중 예외)가 공유하는 시나리오. 유일한 차이는
	 * {@code dispatchService}를 어떻게 모킹하는지와, 그 결과 DLQ 헤더에 남아야 할 사유 코드뿐이다 —
	 * 나머지(워커 드레이너 배선, 발행, latch 대기, TTL 만큼 기다린 뒤 재배달이 없는지 확인, 큐 상태
	 * 검증)는 완전히 같다. (코드리뷰 nitpick: 두 테스트에 걸쳐 중복되던 ~40줄을 여기로 뽑았다.)
	 */
	private void assertMessageIsRetriedFiveTimesThenLandsInTheDlq(
		long questionId, Consumer<QuestionAnswerJobDispatchService> stubDispatch, String expectedDlqReasonCode
	) throws Exception {
		QuestionAnswerJobDispatchService dispatchService = mock(QuestionAnswerJobDispatchService.class);
		stubDispatch.accept(dispatchService);
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

		publishRaw(dispatchMessageJson(questionId).getBytes(StandardCharsets.UTF_8));

		assertThat(dlqLatch.await(20, TimeUnit.SECONDS))
			.as("message must land in the DLQ after MAX_DELIVERY_ATTEMPTS retries")
			.isTrue();

		// no further redelivery should happen once the message has landed in the DLQ
		Thread.sleep(RETRY_TTL_MS_FOR_TEST + 500L);

		// The DLQ message is still unacked (deliberately) on dlqDrainer's channel, so RabbitMQ's
		// passive-declare message count (which reports only "ready" messages) would read 0 while
		// that consumer holds it. Closing the channel makes the broker requeue the unacked message,
		// turning it back into a "ready" message so the count below is meaningful.
		dlqDrainer.stop();
		dlqDrainer = null;
		Thread.sleep(200);

		assertThat(workDeliveries.get())
			.as("exactly one initial delivery + MAX_DELIVERY_ATTEMPTS retries")
			.isEqualTo(1 + AiJobTopology.MAX_DELIVERY_ATTEMPTS);
		assertThat(dlqHeaders).hasSize(1);
		// raw rabbitmq-client decodes AMQP header string values as LongString, not java.lang.String —
		// compare via toString() rather than isEqualTo(String) reference/type equality.
		assertThat(String.valueOf(dlqHeaders.get(0).get(AiJobDeadLetterPublisher.HEADER_DLQ_REASON)))
			.isEqualTo(expectedDlqReasonCode);
		assertThat(dlqHeaders.get(0).get("x-death")).as("x-death must be preserved on the DLQ message").isNotNull();
		assertThat(queueMessageCount(WORK_QUEUE)).isZero();
		assertThat(queueMessageCount(RETRY_QUEUE)).isZero();
		assertThat(queueMessageCount(DLQ)).isEqualTo(1);
	}

	private long queueMessageCount(String queue) {
		var info = admin.getQueueInfo(queue);
		return info == null ? -1 : info.getMessageCount();
	}

	private void publishRaw(byte[] body) throws Exception {
		ConnectionFactory rawFactory = new ConnectionFactory();
		rawFactory.setHost(AiJobRabbitContainer.host());
		rawFactory.setPort(AiJobRabbitContainer.amqpPort());
		rawFactory.setUsername(AiJobRabbitContainer.username());
		rawFactory.setPassword(AiJobRabbitContainer.password());
		try (Connection connection = rawFactory.newConnection(); Channel channel = connection.createChannel()) {
			channel.basicPublish(AiJobTopology.EXCHANGE_JOBS, ROUTING_KEY, null, body);
		}
	}

	private static String dispatchMessageJson(long questionId) {
		return """
			{"schemaVersion":1,"jobId":"%s","jobType":"question_answer_dispatch",
			 "questionId":%d,"reason":"created","occurredAt":"%s"}
			""".formatted(UUID.randomUUID(), questionId, OffsetDateTime.now());
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
