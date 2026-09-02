package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;
import shinhan.fibri.ieum.main.ai.outbox.repository.ClaimedAiJob;

/**
 * relay 정산 경로. spec.md §7.3/§7.5, Task 4 브리프.
 *
 * <p>여기서는 브로커도 DB도 모킹한다 — 검증 대상은 "confirm 결과가 어떤 상태 전이로 이어지는가"와
 * "클레임 → 브로커 → 정산" 호출 순서뿐이다. 실제 트랜잭션 경계가 지켜지는지는
 * {@code AiJobOutboxRelayTransactionBoundaryIntegrationTest}가 진짜 DB로 증명한다.
 */
class AiJobOutboxRelayTest {

	private static final String PAYLOAD =
		"{\"schemaVersion\":1,\"jobId\":\"%s\",\"jobType\":\"question_answer_dispatch\",\"questionId\":42}";

	private AiJobOutboxRepository repository;
	private RabbitTemplate rabbitTemplate;
	private AiJobOutboxRelay relay;

	@BeforeEach
	void setUp() {
		repository = mock(AiJobOutboxRepository.class);
		rabbitTemplate = mock(RabbitTemplate.class);
		relay = new AiJobOutboxRelay(repository, rabbitTemplate, properties(8));
	}

	private AiJobOutboxProperties properties(int maxAttempts) {
		return new AiJobOutboxProperties(
			"worker-1",
			Duration.ofSeconds(60),
			maxAttempts,
			32,
			Duration.ofMillis(200),
			7,
			100
		);
	}

	private ClaimedAiJob claimedJob(int attempts) {
		UUID jobId = UUID.randomUUID();
		return new ClaimedAiJob(
			11L,
			jobId,
			"question_answer_dispatch",
			42L,
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH,
			AiJobTopology.SCHEMA_VERSION,
			PAYLOAD.formatted(jobId),
			attempts
		);
	}

	private void stubClaim(ClaimedAiJob job) {
		when(repository.claim(anyString(), any(UUID.class), anyLong(), anyInt())).thenReturn(List.of(job));
	}

	private void answerSend(SendBehaviour behaviour) {
		doAnswer(invocation -> {
			CorrelationData correlation = invocation.getArgument(3);
			behaviour.apply(invocation.getArgument(2), correlation);
			return null;
		}).when(rabbitTemplate)
			.send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
	}

	@FunctionalInterface
	private interface SendBehaviour {
		void apply(Message message, CorrelationData correlation);
	}

	@Test
	void confirmAckMarksTheRowPublished() {
		ClaimedAiJob job = claimedJob(1);
		stubClaim(job);
		answerSend((message, correlation) -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

		assertThat(relay.publishBatch()).isEqualTo(1);

		verify(repository).markPublished(eq(11L), any(UUID.class));
		verify(repository, never()).markRetry(anyLong(), any(UUID.class), anyLong(), anyString(), anyString());
		verify(repository, never()).markDead(anyLong(), any(UUID.class), anyString(), anyString());
	}

	@Test
	void publishesPersistentJsonMessageCarryingTheStoredPayload() {
		ClaimedAiJob job = claimedJob(1);
		stubClaim(job);
		answerSend((message, correlation) -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

		relay.publishBatch();

		ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
		verify(rabbitTemplate).send(
			eq(AiJobTopology.EXCHANGE_JOBS),
			eq(AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH),
			message.capture(),
			any(CorrelationData.class)
		);
		MessageProperties properties = message.getValue().getMessageProperties();
		assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo(job.payload());
		assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
		assertThat(properties.getContentEncoding()).isEqualTo("UTF-8");
		assertThat(properties.getMessageId()).isEqualTo(job.jobId().toString());
		assertThat(properties.getCorrelationId()).isEqualTo(job.jobId().toString());
		assertThat(properties.getType()).isEqualTo(job.jobType());
		assertThat(properties.getAppId()).isEqualTo(AiJobOutboxRelay.APP_ID);
	}

	@Test
	void confirmNackSchedulesRetryWithBackoff() {
		ClaimedAiJob job = claimedJob(3);
		stubClaim(job);
		answerSend((message, correlation) ->
			correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker said no")));

		relay.publishBatch();

		// attempts=3 -> 2^2 = 4초
		verify(repository).markRetry(eq(11L), any(UUID.class), eq(4L), eq("nack"), anyString());
		verify(repository, never()).markPublished(anyLong(), any(UUID.class));
	}

	@Test
	void returnedMessageSchedulesRetryWithReturnedErrorCode() {
		ClaimedAiJob job = claimedJob(1);
		stubClaim(job);
		answerSend((message, correlation) -> {
			correlation.setReturned(new ReturnedMessage(
				message, 312, "NO_ROUTE",
				AiJobTopology.EXCHANGE_JOBS, AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH
			));
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
		});

		relay.publishBatch();

		verify(repository).markRetry(eq(11L), any(UUID.class), eq(1L), eq("returned"), anyString());
		verify(repository, never()).markPublished(anyLong(), any(UUID.class));
	}

	@Test
	void confirmTimeoutSchedulesRetry() {
		ClaimedAiJob job = claimedJob(2);
		stubClaim(job);
		answerSend((message, correlation) -> {
			// confirm 을 끝내 받지 못한 상황. future 를 완료하지 않는다.
		});

		relay.publishBatch();

		verify(repository).markRetry(eq(11L), any(UUID.class), eq(2L), eq("confirm_timeout"), anyString());
	}

	@Test
	void brokerExceptionSchedulesRetry() {
		ClaimedAiJob job = claimedJob(1);
		stubClaim(job);
		doAnswer(invocation -> {
			throw new AmqpException("connection refused");
		}).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		relay.publishBatch();

		verify(repository).markRetry(eq(11L), any(UUID.class), eq(1L), eq("publish_failed"), anyString());
	}

	@Test
	void attemptsAtOrAboveMaxAttemptsMarkTheRowDead() {
		ClaimedAiJob job = claimedJob(8);
		stubClaim(job);
		answerSend((message, correlation) ->
			correlation.getFuture().complete(new CorrelationData.Confirm(false, "still down")));

		relay.publishBatch();

		verify(repository).markDead(eq(11L), any(UUID.class), eq("nack"), anyString());
		verify(repository, never()).markRetry(anyLong(), any(UUID.class), anyLong(), anyString(), anyString());
	}

	@Test
	void unknownRoutingKeyIsDeadLetteredWithoutTouchingTheBroker() {
		UUID jobId = UUID.randomUUID();
		ClaimedAiJob job = new ClaimedAiJob(
			11L, jobId, "question_answer_dispatch", 42L, "ai.unknown.thing",
			AiJobTopology.SCHEMA_VERSION, PAYLOAD.formatted(jobId), 1
		);
		stubClaim(job);

		relay.publishBatch();

		verify(repository).markDead(eq(11L), any(UUID.class), eq("unknown_routing_key"), anyString());
		verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
	}

	@Test
	void claimsThenPublishesThenSettlesInThatOrder() {
		ClaimedAiJob job = claimedJob(1);
		stubClaim(job);
		answerSend((message, correlation) -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

		relay.publishBatch();

		InOrder ordered = inOrder(repository, rabbitTemplate);
		ordered.verify(repository).claim(anyString(), any(UUID.class), anyLong(), anyInt());
		ordered.verify(rabbitTemplate)
			.send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		ordered.verify(repository).markPublished(eq(11L), any(UUID.class));
	}

	@Test
	void emptyClaimDoesNotTouchTheBroker() {
		when(repository.claim(anyString(), any(UUID.class), anyLong(), anyInt())).thenReturn(List.of());

		assertThat(relay.publishBatch()).isZero();

		verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
	}
}
