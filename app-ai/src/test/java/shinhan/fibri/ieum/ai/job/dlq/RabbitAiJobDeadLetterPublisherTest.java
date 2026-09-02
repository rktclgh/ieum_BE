package shinhan.fibri.ieum.ai.job.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * {@link RabbitAiJobDeadLetterPublisher} 단위 테스트 (채널 모킹). 브리프 "먼저 쓸 테스트" —
 * spec.md §6.3의 재시도 상한 판정과, republish 확인(publisher confirm + mandatory + return listener)
 * 없이는 원본을 ACK 하지 않는다는 안전장치를 실제 broker 없이 검증한다.
 */
class RabbitAiJobDeadLetterPublisherTest {

	private static final String REASON_CODE = "dispatch_saturated";

	private final Channel channel = mock(Channel.class);
	private final RabbitAiJobDeadLetterPublisher publisher = new RabbitAiJobDeadLetterPublisher();

	@BeforeEach
	void stubReturnListenerRegistration() {
		// addReturnListener(ReturnCallback) returns a ReturnListener handle the impl later removes —
		// a mock is enough since we only assert removeReturnListener was called with *something*.
		when(channel.addReturnListener(any(ReturnCallback.class))).thenReturn(mock(ReturnListener.class));
	}

	@Test
	void deathCountBelowMaxAttemptsIsNackedWithoutRequeue() throws Exception {
		Message message = messageWithDeathCount(AiJobTopology.MAX_DELIVERY_ATTEMPTS - 1L);

		publisher.retryOrDeadLetter(channel, 7L, message, REASON_CODE);

		verify(channel).basicNack(7L, false, false);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicPublish(
			anyString(), anyString(), anyBoolean(), any(AMQP.BasicProperties.class), any(byte[].class)
		);
	}

	@Test
	void missingXDeathHeaderIsTreatedAsCountZeroAndNacked() throws Exception {
		Message message = messageWithoutXDeath();

		publisher.retryOrDeadLetter(channel, 7L, message, REASON_CODE);

		verify(channel).basicNack(7L, false, false);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
	}

	@Test
	void deathCountAtMaxAttemptsRepublishesToDlqAndAcksTheOriginalWhenConfirmed() throws Exception {
		when(channel.waitForConfirms(anyLong())).thenReturn(true);
		Message message = messageWithDeathCount(AiJobTopology.MAX_DELIVERY_ATTEMPTS);

		publisher.retryOrDeadLetter(channel, 7L, message, REASON_CODE);

		ArgumentCaptor<AMQP.BasicProperties> propsCaptor = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
		verify(channel).basicPublish(
			eq(AiJobTopology.EXCHANGE_DLX),
			eq(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ),
			eq(true),
			propsCaptor.capture(),
			eq(message.getBody())
		);
		verify(channel).basicAck(7L, false);
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());

		Map<String, Object> headers = propsCaptor.getValue().getHeaders();
		assertThat(headers.get(AiJobDeadLetterPublisher.HEADER_DLQ_REASON)).isEqualTo(REASON_CODE);
		assertThat(headers.get("x-death")).isNotNull();
	}

	@Test
	void deathCountAboveMaxAttemptsAlsoRepublishesToDlqWhenConfirmed() throws Exception {
		when(channel.waitForConfirms(anyLong())).thenReturn(true);
		Message message = messageWithDeathCount(AiJobTopology.MAX_DELIVERY_ATTEMPTS + 3L);

		publisher.retryOrDeadLetter(channel, 7L, message, REASON_CODE);

		verify(channel).basicAck(7L, false);
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	@Test
	void deadLetterAlwaysRepublishesRegardlessOfDeathCountWhenConfirmed() throws Exception {
		when(channel.waitForConfirms(anyLong())).thenReturn(true);
		Message message = messageWithoutXDeath();

		publisher.deadLetter(channel, 7L, message, "unparseable_payload");

		ArgumentCaptor<AMQP.BasicProperties> propsCaptor = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
		verify(channel).basicPublish(
			eq(AiJobTopology.EXCHANGE_DLX),
			eq(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ),
			eq(true),
			propsCaptor.capture(),
			eq(message.getBody())
		);
		verify(channel).basicAck(7L, false);
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
		assertThat(propsCaptor.getValue().getHeaders().get(AiJobDeadLetterPublisher.HEADER_DLQ_REASON))
			.isEqualTo("unparseable_payload");
	}

	@Test
	void unconfirmedRepublishLeavesTheOriginalNackedInsteadOfAcked() throws Exception {
		// broker never confirmed the publish within the timeout (e.g. it nacked it, or confirm
		// never arrived) — must not ACK the original, or the message would be lost.
		when(channel.waitForConfirms(anyLong())).thenReturn(false);
		Message message = messageWithoutXDeath();

		publisher.deadLetter(channel, 7L, message, "unparseable_payload");

		verify(channel).basicPublish(
			eq(AiJobTopology.EXCHANGE_DLX), eq(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ),
			eq(true), any(AMQP.BasicProperties.class), any(byte[].class)
		);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel).basicNack(7L, false, false);
	}

	@Test
	void confirmTimeoutLeavesTheOriginalNackedInsteadOfAcked() throws Exception {
		when(channel.waitForConfirms(anyLong())).thenThrow(new TimeoutException("no confirm in time"));
		Message message = messageWithoutXDeath();

		publisher.deadLetter(channel, 7L, message, "unparseable_payload");

		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel).basicNack(7L, false, false);
	}

	@Test
	void returnedMessageLeavesTheOriginalNackedEvenIfTheConfirmArrivesOk() throws Exception {
		// RabbitMQ can both confirm a publish (broker accepted/processed it) AND return it
		// (unroutable) — confirm alone does not prove the message reached the DLQ.
		when(channel.waitForConfirms(anyLong())).thenReturn(true);
		ArgumentCaptor<ReturnCallback> returnCallbackCaptor = ArgumentCaptor.forClass(ReturnCallback.class);
		when(channel.addReturnListener(returnCallbackCaptor.capture())).thenReturn(mock(ReturnListener.class));
		doAnswer(invocation -> {
			returnCallbackCaptor.getValue().handle(new Return(
				312, "NO_ROUTE", AiJobTopology.EXCHANGE_DLX, "unbound.routing.key",
				new AMQP.BasicProperties(), new byte[0]
			));
			return null;
		}).when(channel).basicPublish(
			anyString(), anyString(), eq(true), any(AMQP.BasicProperties.class), any(byte[].class)
		);
		Message message = messageWithoutXDeath();

		publisher.deadLetter(channel, 7L, message, "unparseable_payload");

		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel).basicNack(7L, false, false);
	}

	@Test
	void missingConsumerQueueFailsFastRatherThanPublishingToANullDlq() {
		MessageProperties properties = new MessageProperties();
		// consumerQueue deliberately left unset
		Message message = new Message("{\"schemaVersion\":1}".getBytes(), properties);

		org.junit.jupiter.api.Assertions.assertThrows(
			NullPointerException.class, () -> publisher.deadLetter(channel, 7L, message, "unparseable_payload")
		);
	}

	private static Message messageWithDeathCount(long count) {
		MessageProperties properties = new MessageProperties();
		properties.setConsumerQueue(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH);
		properties.setHeader("x-death", List.of(
			Map.of("count", count, "queue", "some-retry-queue", "reason", "expired")
		));
		return new Message("{\"schemaVersion\":1}".getBytes(), properties);
	}

	private static Message messageWithoutXDeath() {
		MessageProperties properties = new MessageProperties();
		properties.setConsumerQueue(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH);
		return new Message("{\"schemaVersion\":1}".getBytes(), properties);
	}
}
