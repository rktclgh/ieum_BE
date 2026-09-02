package shinhan.fibri.ieum.ai.job.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * {@link RabbitAiJobDeadLetterPublisher} 단위 테스트 (채널 모킹). 브리프 "먼저 쓸 테스트" —
 * spec.md §6.3의 재시도 상한 판정을 실제 broker 없이 검증한다.
 */
class RabbitAiJobDeadLetterPublisherTest {

	private static final String REASON_CODE = "dispatch_saturated";

	private final Channel channel = mock(Channel.class);
	private final RabbitAiJobDeadLetterPublisher publisher = new RabbitAiJobDeadLetterPublisher();

	@Test
	void deathCountBelowMaxAttemptsIsNackedWithoutRequeue() throws Exception {
		Message message = messageWithDeathCount(AiJobTopology.MAX_DELIVERY_ATTEMPTS - 1L);

		publisher.retryOrDeadLetter(channel, 7L, message, REASON_CODE);

		verify(channel).basicNack(7L, false, false);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicPublish(
			anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class)
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
	void deathCountAtMaxAttemptsRepublishesToDlqAndAcksTheOriginal() throws Exception {
		Message message = messageWithDeathCount(AiJobTopology.MAX_DELIVERY_ATTEMPTS);

		publisher.retryOrDeadLetter(channel, 7L, message, REASON_CODE);

		ArgumentCaptor<AMQP.BasicProperties> propsCaptor = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
		verify(channel).basicPublish(
			eq(AiJobTopology.EXCHANGE_DLX),
			eq(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ),
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
	void deathCountAboveMaxAttemptsAlsoRepublishesToDlq() throws Exception {
		Message message = messageWithDeathCount(AiJobTopology.MAX_DELIVERY_ATTEMPTS + 3L);

		publisher.retryOrDeadLetter(channel, 7L, message, REASON_CODE);

		verify(channel).basicAck(7L, false);
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	@Test
	void deadLetterAlwaysRepublishesRegardlessOfDeathCount() throws Exception {
		Message message = messageWithoutXDeath();

		publisher.deadLetter(channel, 7L, message, "unparseable_payload");

		ArgumentCaptor<AMQP.BasicProperties> propsCaptor = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
		verify(channel).basicPublish(
			eq(AiJobTopology.EXCHANGE_DLX),
			eq(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ),
			propsCaptor.capture(),
			eq(message.getBody())
		);
		verify(channel).basicAck(7L, false);
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
		assertThat(propsCaptor.getValue().getHeaders().get(AiJobDeadLetterPublisher.HEADER_DLQ_REASON))
			.isEqualTo("unparseable_payload");
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
