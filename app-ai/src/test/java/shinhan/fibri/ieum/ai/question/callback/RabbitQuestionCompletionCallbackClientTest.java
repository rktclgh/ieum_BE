package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * {@link QuestionCompletionCallbackClient} 계약을 RabbitMQ 전송으로 만족시키는지 검증한다.
 * 브리프 "먼저 쓸 테스트" 1번. spec.md §8.5.
 *
 * <p>{@link RabbitTemplate}을 모킹하고 {@link CorrelationData#getFuture()}를 직접 완료시켜
 * confirm/nack/timeout 분기를 재현한다 — {@code AiJobOutboxRelayTest}와 같은 방식이다.
 */
class RabbitQuestionCompletionCallbackClientTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
	private final RabbitQuestionCompletionCallbackClient client =
		new RabbitQuestionCompletionCallbackClient(rabbitTemplate, objectMapper, Duration.ofMillis(200));

	@BeforeEach
	void stubDefaultConfirmedSend() {
		answerSend((message, correlation) -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));
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
	void confirmedPublishIsDelivered() {
		CallbackHttpResult result = client.deliver(42L, 99L);

		assertThat(result).isEqualTo(CallbackHttpResult.DELIVERED);
	}

	@Test
	void publishesToTheResultsExchangeWithTheCompletedRoutingKeyAndPersistentJsonBody() {
		client.deliver(42L, 99L);

		org.mockito.ArgumentCaptor<Message> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
		verify(rabbitTemplate).send(
			eq(AiJobTopology.EXCHANGE_RESULTS),
			eq(AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED),
			captor.capture(),
			any(CorrelationData.class)
		);
		Message sent = captor.getValue();
		MessageProperties properties = sent.getMessageProperties();
		assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
		assertThat(properties.getAppId()).isEqualTo("ieum-app-ai");
		String body = new String(sent.getBody(), StandardCharsets.UTF_8);
		assertThat(body).contains("\"questionId\":42").contains("\"answerId\":99")
			.contains("\"jobType\":\"question_answer_completed\"")
			.contains("\"schemaVersion\":" + AiJobTopology.SCHEMA_VERSION);
	}

	@Test
	void confirmNackIsFailed() {
		answerSend((message, correlation) ->
			correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker said no")));

		assertThat(client.deliver(42L, 99L)).isEqualTo(CallbackHttpResult.FAILED);
	}

	@Test
	void mandatoryReturnIsFailed() {
		answerSend((message, correlation) -> {
			correlation.setReturned(new ReturnedMessage(
				message, 312, "NO_ROUTE", AiJobTopology.EXCHANGE_RESULTS,
				AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED
			));
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
		});

		assertThat(client.deliver(42L, 99L)).isEqualTo(CallbackHttpResult.FAILED);
	}

	@Test
	void confirmTimeoutIsFailed() {
		answerSend((message, correlation) -> {
			// 절대 완료하지 않는다 — confirmTimeout(200ms) 후 TimeoutException 경로를 태운다.
		});

		assertThat(client.deliver(42L, 99L)).isEqualTo(CallbackHttpResult.FAILED);
	}

	@Test
	void publishExceptionIsFailed() {
		doThrow(new AmqpException("broker unreachable"))
			.when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		assertThat(client.deliver(42L, 99L)).isEqualTo(CallbackHttpResult.FAILED);
	}

	@Test
	void nonPositiveQuestionIdIsRejected() {
		assertThatThrownBy(() -> client.deliver(0L, 99L)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.deliver(-1L, 99L)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nonPositiveAnswerIdIsRejected() {
		assertThatThrownBy(() -> client.deliver(42L, 0L)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.deliver(42L, -1L)).isInstanceOf(IllegalArgumentException.class);
	}
}
