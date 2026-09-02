package shinhan.fibri.ieum.main.ai.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.result.dlq.AiResultDeadLetterPublisher;
import shinhan.fibri.ieum.main.notification.internal.AiQuestionAnswerCompletionConflictException;
import shinhan.fibri.ieum.main.notification.internal.AiQuestionAnswerCompletionService;
import shinhan.fibri.ieum.main.notification.internal.AiQuestionAnswerTicketNotFoundException;

/**
 * {@code ai.question-answer.completed} 결과 큐 컨슈머. spec.md §8.4.
 *
 * <p>기존 {@link AiQuestionAnswerCompletionService#complete(Long, Long)}을 <b>그대로 재사용</b>한다 —
 * 새 도메인 로직을 두지 않는다. 오늘의 HTTP 콜백 컨트롤러가 하는 일과 정확히 같은 서비스 호출이고,
 * 정산만 HTTP 상태 코드 대신 AMQP ACK/NACK/DLQ 로 매핑한다:
 *
 * <table>
 *   <caption>spec.md §8.4 매핑</caption>
 *   <tr><th>결과</th><th>오늘의 HTTP</th><th>MQ 정산</th></tr>
 *   <tr><td>정상 완료</td><td>204</td><td>ACK</td></tr>
 *   <tr><td>이미 ACK된 멱등 재시도</td><td>204</td><td>ACK</td></tr>
 *   <tr><td>삭제된 질문(알림 없이 티켓만 ACK)</td><td>204</td><td>ACK</td></tr>
 *   <tr><td>{@link AiQuestionAnswerTicketNotFoundException}</td><td>404</td><td>즉시 DLQ
 *       ({@code ticket_not_found})</td></tr>
 *   <tr><td>{@link AiQuestionAnswerCompletionConflictException}</td><td>409</td><td>즉시 DLQ
 *       ({@code completion_conflict}) — 상태 충돌은 재시도로 고쳐지지 않는다</td></tr>
 *   <tr><td>본문 파싱 실패 / 스키마 위반</td><td>400</td><td>즉시 DLQ</td></tr>
 *   <tr><td>DB transient 오류</td><td>5xx</td><td>NACK → retry 큐</td></tr>
 * </table>
 *
 * <p><b>{@code InvalidInternalAiTokenException}(오늘의 401)은 이 경로에 존재하지 않는다.</b> 토큰
 * 검증은 HTTP 표면 전용이다 — MQ 경로의 인증은 브로커 자격증명 + vhost 권한이 대신한다(spec.md §10).
 *
 * <p>처음 세 행(정상 완료/멱등 재시도/삭제된 질문)은 리스너 입장에서 구분되지 않는다 —
 * {@code complete(...)}가 예외 없이 반환하면 전부 ACK 다. 그 상태 구분은 서비스 내부의 도메인
 * 로직(락, notificationProcessedAt 체크, deleted 플래그)이 이미 하고 있고, 이 리스너는 그 결과에
 * 개입하지 않는다.
 *
 * <p><b>활성 플래그는 {@code app.ai.outbox.enabled}가 아니다.</b> 그 플래그는 app-main 의 디스패치
 * 발행 relay(Stage 3, spec.md §11.5)를 켠다. 이 리스너는 별도의 {@code app.ai.result.consumer.enabled}
 * 로 게이트된다(기본값 {@code true}) — spec.md §11.5 롤아웃 Stage 2는 app-ai 쪽 플래그
 * (`APP_AI_QUESTION_CALLBACK_TRANSPORT=rabbitmq`, `APP_AI_COMPLETION_RELAY_ENABLED=true`)만 뒤집고
 * app-main 은 아무 것도 바꾸지 않은 채로 "app-main 결과 컨슈머가 받는다"고 명시하므로, 이 리스너는
 * outbox 플래그와 무관하게 기본적으로 켜져 있어야 한다 — app-ai 의 디스패치 컨슈머가 항상 켜져 있는
 * 것과 같은 이유·같은 패턴이다. 이 리스너가 필요로 하는 큐/exchange 토폴로지는
 * {@link shinhan.fibri.ieum.config.AiResultRabbitConfig}가 같은 이름의 프로퍼티(둘 중 하나라도
 * 참이면) 로 독립적으로 선언한다 — 리뷰 라운드 1 finding.
 */
@Component
@ConditionalOnProperty(name = "app.ai.result.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class QuestionAnswerCompletedMessageListener {

	private static final Logger log = LoggerFactory.getLogger(QuestionAnswerCompletedMessageListener.class);

	private final AiQuestionAnswerCompletionService completionService;
	private final AiResultDeadLetterPublisher deadLetterPublisher;
	private final ObjectMapper objectMapper;

	public QuestionAnswerCompletedMessageListener(
		AiQuestionAnswerCompletionService completionService,
		AiResultDeadLetterPublisher deadLetterPublisher,
		ObjectMapper objectMapper
	) {
		this.completionService = completionService;
		this.deadLetterPublisher = deadLetterPublisher;
		this.objectMapper = objectMapper;
	}

	@RabbitListener(queues = AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED)
	public void onMessage(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
		throws IOException {
		JsonNode root;
		try {
			root = objectMapper.readTree(message.getBody());
		}
		catch (IOException exception) {
			dlq(message, channel, deliveryTag, AiResultMessageSettlement.REASON_UNPARSEABLE_PAYLOAD);
			return;
		}
		if (root == null || root.isMissingNode() || root.isNull()) {
			dlq(message, channel, deliveryTag, AiResultMessageSettlement.REASON_UNPARSEABLE_PAYLOAD);
			return;
		}

		Optional<String> schemaViolation =
			AiResultMessageSettlement.validateSchemaVersion(readIntOrNull(root, "schemaVersion"));
		if (schemaViolation.isPresent()) {
			dlq(message, channel, deliveryTag, schemaViolation.get());
			return;
		}

		long questionId = root.path("questionId").asLong(0);
		long answerId = root.path("answerId").asLong(0);
		if (questionId <= 0 || answerId <= 0) {
			dlq(message, channel, deliveryTag, AiResultMessageSettlement.REASON_INVALID_PAYLOAD);
			return;
		}

		try {
			completionService.complete(questionId, answerId);
			log.info(
				"event=ai_result_consumed jobType=question_answer_completed questionId={} answerId={}",
				questionId, answerId
			);
			channel.basicAck(deliveryTag, false);
		}
		catch (AiQuestionAnswerTicketNotFoundException exception) {
			dlq(message, channel, deliveryTag, AiResultMessageSettlement.REASON_TICKET_NOT_FOUND);
		}
		catch (AiQuestionAnswerCompletionConflictException exception) {
			dlq(message, channel, deliveryTag, AiResultMessageSettlement.REASON_COMPLETION_CONFLICT);
		}
		catch (RuntimeException exception) {
			// DB transient 오류(spec.md §8.4 "5xx"). 재시도로 나아질 수 있는 실패이므로 NACK 이다 —
			// x-death 상한 판정은 deadLetterPublisher 에 위임한다.
			log.warn(
				"event=ai_result_transient_failure questionId={} answerId={} failureType={}",
				questionId, answerId, exception.getClass().getSimpleName()
			);
			retry(message, channel, deliveryTag, AiResultMessageSettlement.REASON_TRANSIENT_ERROR);
		}
	}

	private void retry(Message message, Channel channel, long deliveryTag, String reasonCode) throws IOException {
		deadLetterPublisher.retryOrDeadLetter(channel, deliveryTag, message, reasonCode);
	}

	private void dlq(Message message, Channel channel, long deliveryTag, String reasonCode) throws IOException {
		log.error("event=ai_result_dead_lettered jobType=question_answer_completed reasonCode={}", reasonCode);
		deadLetterPublisher.deadLetter(channel, deliveryTag, message, reasonCode);
	}

	private static Integer readIntOrNull(JsonNode root, String field) {
		JsonNode node = root.get(field);
		if (node == null || node.isNull() || !node.canConvertToInt()) {
			return null;
		}
		return node.intValue();
	}
}
