package shinhan.fibri.ieum.main.ai.result;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.QueryTimeoutException;
import shinhan.fibri.ieum.main.ai.result.dlq.AiResultDeadLetterPublisher;
import shinhan.fibri.ieum.main.notification.internal.AiQuestionAnswerCompletionConflictException;
import shinhan.fibri.ieum.main.notification.internal.AiQuestionAnswerCompletionService;
import shinhan.fibri.ieum.main.notification.internal.AiQuestionAnswerTicketNotFoundException;

/**
 * spec.md §8.4 매핑 표를 한 행씩 검증한다. {@link AiQuestionAnswerCompletionService}는 모킹한다.
 * 브리프 "먼저 쓸 테스트" 4번.
 */
class QuestionAnswerCompletedMessageListenerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AiQuestionAnswerCompletionService completionService = mock(AiQuestionAnswerCompletionService.class);
	private final AiResultDeadLetterPublisher deadLetterPublisher = mock(AiResultDeadLetterPublisher.class);
	private final Channel channel = mock(Channel.class);
	private final QuestionAnswerCompletedMessageListener listener =
		new QuestionAnswerCompletedMessageListener(completionService, deadLetterPublisher, objectMapper);

	// --- 매핑 표 행 1: 정상 완료 → ACK ---
	@Test
	void normalCompletionIsAcknowledged() throws Exception {
		doNothing().when(completionService).complete(42L, 99L);

		listener.onMessage(validMessage(42L, 99L), channel, 7L);

		verify(channel).basicAck(7L, false);
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
		verifyNoInteractions(deadLetterPublisher);
	}

	// --- 매핑 표 행 2: 이미 ACK된 멱등 재시도 → ACK (complete()가 내부적으로 조용히 반환) ---
	@Test
	void idempotentRetryOfAnAlreadyAcknowledgedTicketIsAcknowledged() throws Exception {
		doNothing().when(completionService).complete(42L, 99L);

		listener.onMessage(validMessage(42L, 99L), channel, 7L);

		verify(channel).basicAck(7L, false);
		verifyNoInteractions(deadLetterPublisher);
	}

	// --- 매핑 표 행 3: 삭제된 질문(알림 없이 티켓만 ACK) → ACK (complete()가 예외 없이 반환) ---
	@Test
	void deletedQuestionStillAcknowledges() throws Exception {
		doNothing().when(completionService).complete(42L, 99L);

		listener.onMessage(validMessage(42L, 99L), channel, 7L);

		verify(channel).basicAck(7L, false);
		verifyNoInteractions(deadLetterPublisher);
	}

	// --- 매핑 표 행 4: AiQuestionAnswerTicketNotFoundException → 즉시 DLQ ---
	@Test
	void ticketNotFoundIsDeadLettered() throws Exception {
		doThrow(new AiQuestionAnswerTicketNotFoundException()).when(completionService).complete(42L, 99L);
		Message message = validMessage(42L, 99L);

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			channel, 7L, message, AiResultMessageSettlement.REASON_TICKET_NOT_FOUND
		);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	// --- 매핑 표 행 5: AiQuestionAnswerCompletionConflictException → 즉시 DLQ ---
	@Test
	void completionConflictIsDeadLettered() throws Exception {
		doThrow(new AiQuestionAnswerCompletionConflictException()).when(completionService).complete(42L, 99L);
		Message message = validMessage(42L, 99L);

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			channel, 7L, message, AiResultMessageSettlement.REASON_COMPLETION_CONFLICT
		);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	// --- 매핑 표 행 6: 본문 파싱 실패 → 즉시 DLQ ---
	@Test
	void unparseablePayloadIsDeadLetteredWithoutCallingTheCompletionService() throws Exception {
		Message message = rawMessage("not json at all {{{".getBytes(StandardCharsets.UTF_8));

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiResultMessageSettlement.REASON_UNPARSEABLE_PAYLOAD)
		);
		verifyNoInteractions(completionService);
	}

	// --- 매핑 표 행 7: DB transient 오류 → NACK(retryOrDeadLetter 위임) ---
	@Test
	void transientDatabaseFailureDelegatesToTheDeadLetterPublisherForRetryOrDeadLetterDecision() throws Exception {
		doThrow(new QueryTimeoutException("simulated transient failure")).when(completionService).complete(42L, 99L);
		Message message = validMessage(42L, 99L);

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).retryOrDeadLetter(
			channel, 7L, message, AiResultMessageSettlement.REASON_TRANSIENT_ERROR
		);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	// --- 부가: 잘못된 ID / 스키마 버전 (app-ai 리스너와 같은 봉투 검증 계약) ---
	@Test
	void nonPositiveQuestionIdIsDeadLetteredWithoutCallingTheCompletionService() throws Exception {
		Message message = validMessage(0L, 99L);

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiResultMessageSettlement.REASON_INVALID_PAYLOAD)
		);
		verifyNoInteractions(completionService);
	}

	@Test
	void nonPositiveAnswerIdIsDeadLetteredWithoutCallingTheCompletionService() throws Exception {
		Message message = validMessage(42L, 0L);

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiResultMessageSettlement.REASON_INVALID_PAYLOAD)
		);
		verifyNoInteractions(completionService);
	}

	@Test
	void missingSchemaVersionIsDeadLetteredWithoutCallingTheCompletionService() throws Exception {
		String json = """
			{"jobId":"11111111-1111-1111-1111-111111111111","jobType":"question_answer_completed",
			 "questionId":42,"answerId":99,"occurredAt":"2026-09-02T09:15:04.512Z"}
			""";
		Message message = rawMessage(json.getBytes(StandardCharsets.UTF_8));

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiResultMessageSettlement.REASON_MISSING_SCHEMA_VERSION)
		);
		verifyNoInteractions(completionService);
	}

	@Test
	void unsupportedSchemaVersionIsDeadLetteredWithoutCallingTheCompletionService() throws Exception {
		String json = """
			{"schemaVersion":99,"jobId":"11111111-1111-1111-1111-111111111111",
			 "jobType":"question_answer_completed","questionId":42,"answerId":99,
			 "occurredAt":"2026-09-02T09:15:04.512Z"}
			""";
		Message message = rawMessage(json.getBytes(StandardCharsets.UTF_8));

		listener.onMessage(message, channel, 7L);

		verify(deadLetterPublisher).deadLetter(
			eq(channel), eq(7L), eq(message), eq(AiResultMessageSettlement.REASON_UNSUPPORTED_SCHEMA_VERSION)
		);
		verifyNoInteractions(completionService);
	}

	private static Message validMessage(long questionId, long answerId) {
		String json = """
			{"schemaVersion":1,"jobId":"11111111-1111-1111-1111-111111111111",
			 "jobType":"question_answer_completed","questionId":%d,"answerId":%d,
			 "occurredAt":"2026-09-02T09:15:04.512Z"}
			""".formatted(questionId, answerId);
		return rawMessage(json.getBytes(StandardCharsets.UTF_8));
	}

	private static Message rawMessage(byte[] body) {
		return new Message(body, new MessageProperties());
	}
}
