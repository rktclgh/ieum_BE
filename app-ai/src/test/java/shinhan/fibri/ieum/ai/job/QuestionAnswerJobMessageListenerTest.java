package shinhan.fibri.ieum.ai.job;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import shinhan.fibri.ieum.ai.job.dlq.AiJobDeadLetterPublisher;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchResult;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchService;

/**
 * spec.md §8.2 매핑 표를 한 행씩 검증한다. {@link QuestionAnswerJobDispatchService}는 모킹한다.
 */
class QuestionAnswerJobMessageListenerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final QuestionAnswerJobDispatchService dispatchService = mock(QuestionAnswerJobDispatchService.class);
	private final AiJobDeadLetterPublisher deadLetterPublisher = mock(AiJobDeadLetterPublisher.class);
	private final Channel channel = mock(Channel.class);
	private final QuestionAnswerJobMessageListener listener =
		new QuestionAnswerJobMessageListener(dispatchService, deadLetterPublisher, objectMapper);

	@ParameterizedTest
	@EnumSource(value = QuestionAnswerJobDispatchResult.class, names = {
		"ENQUEUED", "ALREADY_ACTIVE", "ALREADY_COMPLETED", "CANCELLED_OR_DELETED", "DEAD"
	})
	void ackResultsAreAcknowledged(QuestionAnswerJobDispatchResult result) throws Exception {
		when(dispatchService.dispatch(42L)).thenReturn(result);

		listener.onMessage(validMessage(42L), channel, 7L);

		verify(channel).basicAck(7L, false);
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
		verifyNoInteractions(deadLetterPublisher);
	}

	@ParameterizedTest
	@EnumSource(value = QuestionAnswerJobDispatchResult.class, names = {"SATURATED", "DISABLED"})
	void saturatedOrDisabledResultsAreNackedWithoutRequeue(QuestionAnswerJobDispatchResult result) throws Exception {
		when(dispatchService.dispatch(42L)).thenReturn(result);

		listener.onMessage(validMessage(42L), channel, 7L);

		verify(channel).basicNack(7L, false, false);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verifyNoInteractions(deadLetterPublisher);
	}

	@Test
	void invariantBreachIsDeadLettered() throws Exception {
		when(dispatchService.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.INVARIANT_BREACH);
		Message message = validMessage(42L);

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(channel, 7L, message, AiJobMessageSettlement.REASON_TICKET_NOT_FOUND);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	@Test
	void unparseablePayloadIsDeadLetteredWithoutCallingTheDispatchService() throws Exception {
		Message message = rawMessage("not json at all {{{".getBytes(StandardCharsets.UTF_8));

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiJobMessageSettlement.REASON_UNPARSEABLE_PAYLOAD)
		);
		verifyNoInteractions(dispatchService);
	}

	@Test
	void nonPositiveQuestionIdIsDeadLetteredWithoutCallingTheDispatchService() throws Exception {
		Message message = validMessageWithQuestionId(0L);

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiJobMessageSettlement.REASON_INVALID_PAYLOAD)
		);
		verifyNoInteractions(dispatchService);
	}

	@Test
	void negativeQuestionIdIsDeadLetteredWithoutCallingTheDispatchService() throws Exception {
		Message message = validMessageWithQuestionId(-5L);

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiJobMessageSettlement.REASON_INVALID_PAYLOAD)
		);
		verifyNoInteractions(dispatchService);
	}

	@Test
	void missingSchemaVersionIsDeadLetteredWithoutCallingTheDispatchService() throws Exception {
		String json = """
			{"jobId":"11111111-1111-1111-1111-111111111111","jobType":"question_answer_dispatch",
			 "questionId":42,"reason":"created","occurredAt":"2026-09-02T09:15:04.512Z"}
			""";
		Message message = rawMessage(json.getBytes(StandardCharsets.UTF_8));

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiJobMessageSettlement.REASON_MISSING_SCHEMA_VERSION)
		);
		verifyNoInteractions(dispatchService);
	}

	@Test
	void unsupportedSchemaVersionIsDeadLetteredWithoutCallingTheDispatchService() throws Exception {
		String json = """
			{"schemaVersion":99,"jobId":"11111111-1111-1111-1111-111111111111",
			 "jobType":"question_answer_dispatch","questionId":42,"reason":"created",
			 "occurredAt":"2026-09-02T09:15:04.512Z"}
			""";
		Message message = rawMessage(json.getBytes(StandardCharsets.UTF_8));

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiJobMessageSettlement.REASON_UNSUPPORTED_SCHEMA_VERSION)
		);
		verifyNoInteractions(dispatchService);
	}

	private static Message validMessage(long questionId) {
		return validMessageWithQuestionId(questionId);
	}

	private static Message validMessageWithQuestionId(long questionId) {
		String json = """
			{"schemaVersion":1,"jobId":"11111111-1111-1111-1111-111111111111",
			 "jobType":"question_answer_dispatch","questionId":%d,"reason":"created",
			 "occurredAt":"2026-09-02T09:15:04.512Z"}
			""".formatted(questionId);
		return rawMessage(json.getBytes(StandardCharsets.UTF_8));
	}

	private static Message rawMessage(byte[] body) {
		return new Message(body, new MessageProperties());
	}
}
