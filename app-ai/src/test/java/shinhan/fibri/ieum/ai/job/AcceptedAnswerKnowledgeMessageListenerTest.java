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
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskLane;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskSubmission;

/**
 * spec.md §8.2 "채택 답변 지식화도 동일하다" — {@link AcceptedAnswerKnowledgeTaskLane}은 모킹한다.
 */
class AcceptedAnswerKnowledgeMessageListenerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AcceptedAnswerKnowledgeTaskLane lane = mock(AcceptedAnswerKnowledgeTaskLane.class);
	private final AiJobDeadLetterPublisher deadLetterPublisher = mock(AiJobDeadLetterPublisher.class);
	private final Channel channel = mock(Channel.class);
	private final AcceptedAnswerKnowledgeMessageListener listener =
		new AcceptedAnswerKnowledgeMessageListener(lane, deadLetterPublisher, objectMapper);

	@ParameterizedTest
	@EnumSource(value = AcceptedAnswerKnowledgeTaskSubmission.class, names = {"ENQUEUED", "ALREADY_ACTIVE"})
	void enqueuedOrAlreadyActiveIsAcknowledged(AcceptedAnswerKnowledgeTaskSubmission submission) throws Exception {
		when(lane.submit(99L)).thenReturn(submission);

		listener.onMessage(validMessage(99L), channel, 3L);

		verify(channel).basicAck(3L, false);
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
		verifyNoInteractions(deadLetterPublisher);
	}

	@Test
	void saturatedSubmissionDelegatesToTheDeadLetterPublisherForRetryOrDeadLetterDecision() throws Exception {
		when(lane.submit(99L)).thenReturn(AcceptedAnswerKnowledgeTaskSubmission.SATURATED);
		Message message = validMessage(99L);

		listener.onMessage(message, channel, 3L);

		verify(deadLetterPublisher).retryOrDeadLetter(
			channel, 3L, message, AiJobMessageSettlement.REASON_DISPATCH_SATURATED
		);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	@Test
	void disabledSubmissionDelegatesToTheDeadLetterPublisherForRetryOrDeadLetterDecision() throws Exception {
		when(lane.submit(99L)).thenReturn(AcceptedAnswerKnowledgeTaskSubmission.DISABLED);
		Message message = validMessage(99L);

		listener.onMessage(message, channel, 3L);

		verify(deadLetterPublisher).retryOrDeadLetter(
			channel, 3L, message, AiJobMessageSettlement.REASON_DISPATCH_DISABLED
		);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	@Test
	void dispatchExceptionDelegatesToTheDeadLetterPublisherInsteadOfPropagating() throws Exception {
		when(lane.submit(99L)).thenThrow(new org.springframework.dao.QueryTimeoutException("boom"));
		Message message = validMessage(99L);

		listener.onMessage(message, channel, 3L);

		verify(deadLetterPublisher).retryOrDeadLetter(
			channel, 3L, message, AiJobMessageSettlement.REASON_DISPATCH_EXCEPTION
		);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	@Test
	void nonPositiveAnswerIdIsDeadLetteredWithoutCallingTheLane() throws Exception {
		Message message = validMessage(0L);

		listener.onMessage(message, channel, 3L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(3L), eq(message), eq(AiJobMessageSettlement.REASON_INVALID_PAYLOAD)
		);
		verifyNoInteractions(lane);
	}

	@Test
	void unparseablePayloadIsDeadLetteredWithoutCallingTheLane() throws Exception {
		Message message = rawMessage("{{{not json".getBytes(StandardCharsets.UTF_8));

		listener.onMessage(message, channel, 3L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(3L), eq(message), eq(AiJobMessageSettlement.REASON_UNPARSEABLE_PAYLOAD)
		);
		verifyNoInteractions(lane);
	}

	@Test
	void unsupportedSchemaVersionIsDeadLetteredWithoutCallingTheLane() throws Exception {
		String json = """
			{"schemaVersion":99,"jobId":"22222222-2222-2222-2222-222222222222",
			 "jobType":"accepted_answer_knowledge_ingest","answerId":99,
			 "occurredAt":"2026-09-02T09:20:31.004Z"}
			""";
		Message message = rawMessage(json.getBytes(StandardCharsets.UTF_8));

		listener.onMessage(message, channel, 3L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(3L), eq(message), eq(AiJobMessageSettlement.REASON_UNSUPPORTED_SCHEMA_VERSION)
		);
		verifyNoInteractions(lane);
	}

	private static Message validMessage(long answerId) {
		String json = """
			{"schemaVersion":1,"jobId":"22222222-2222-2222-2222-222222222222",
			 "jobType":"accepted_answer_knowledge_ingest","answerId":%d,
			 "occurredAt":"2026-09-02T09:20:31.004Z"}
			""".formatted(answerId);
		return rawMessage(json.getBytes(StandardCharsets.UTF_8));
	}

	private static Message rawMessage(byte[] body) {
		return new Message(body, new MessageProperties());
	}
}
