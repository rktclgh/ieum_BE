package shinhan.fibri.ieum.ai.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.ai.job.dlq.AiJobDeadLetterPublisher;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchResult;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchService;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * {@code ai.question-answer.dispatch} 큐 컨슈머. spec.md §8.2, §8.3.
 *
 * <p><b>ACK 시점 계약 (spec.md §8.3, "R3 완화"):</b> 이 리스너는 {@link QuestionAnswerJobDispatchService}가
 * <b>lane 제출에 성공한 시점</b>에 ACK 한다 — LLM 추론이 끝나기를 기다리지 않는다. 이유:
 * <ol>
 *   <li>질문 답변 lane 은 분 단위로 걸릴 수 있는데 {@code prefetch=1}에서는 그동안 큐가 완전히 멈춘다.</li>
 *   <li>긴 unacked 메시지는 컨슈머 타임아웃·하트비트 문제를 부른다.</li>
 *   <li>실행 보장은 이미 DB에 있다 — {@code ai_question_tasks}의 status/attempts/lease_until/lease_token
 *       이 진짜 fencing 이고, 크래시 시 lease 만료 복구가 그 일을 한다. 메시지는 "작업 실행"이 아니라
 *       "작업 wake"를 전달할 뿐이다.</li>
 * </ol>
 * NACK 하는 유일한 경우는 wake 자체가 실패했을 때(lane 포화, 기능 비활성)다 — 다만 {@code x-death}
 * 카운트가 상한({@code AiJobTopology.MAX_DELIVERY_ATTEMPTS})에 도달하면 {@link AiJobDeadLetterPublisher}가
 * NACK 대신 DLQ 경로로 전환한다(spec.md §6.3).
 *
 * <p><b>dispatch 중 예외</b>: {@code dispatchService.dispatch(...)}(또는 그 뒤의 {@code settle}) 밖으로
 * 새는 {@code RuntimeException}(예: {@code DataAccessException})을 그대로 흘려보내면 Spring의
 * {@code default-requeue-rejected=false} 처리기가 {@code x-death}를 보지 않고 NACK 하므로 재시도
 * 상한을 절대 만나지 못한 채 work↔retry 큐를 영원히 순환한다. 그래서 이 리스너는 그 예외를 잡아
 * {@link AiJobDeadLetterPublisher#retryOrDeadLetter}로 명시적으로 위임한다
 * ({@link AiJobMessageSettlement#REASON_DISPATCH_EXCEPTION}) — 상한 판정을 다시 정상 경로에 태운다.
 *
 * <p>멱등성은 {@code jobId}가 아니라 {@code ai_question_tasks}의 DB 상태 머신이 보장한다
 * (spec.md §8.2) — {@link QuestionAnswerJobDispatchService#dispatch(long)}가 이미 이 판단을 한다.
 *
 * <p><b>{@code app.ai.dispatch.transport}는 app-ai 컨슈머의 kill switch 다</b> — {@link
 * shinhan.fibri.ieum.ai.config.AiJobRabbitConfiguration}이 선언하는 토폴로지와 <b>반드시 같은
 * 조건</b>으로 켜고 꺼야 한다({@code rabbitmq}(기본값)일 때만 빈이 등록된다). 이 리스너가 토폴로지
 * 없이 무조건 등록되면, transport 가 {@code http}로 바뀐 새 브로커에서 선언되지 않은 큐를 구독하려다
 * 실패한다(리뷰 발견 사항). app-main 의 outbox/transport 플래그와는 별개다 — app-main 만
 * HTTP/rabbitmq 를 오가고, app-ai 는 이 프로퍼티로만 컨슈머 자체를 켜고 끈다.
 */
@Component
@ConditionalOnProperty(
	prefix = "app.ai.dispatch", name = "transport", havingValue = "rabbitmq", matchIfMissing = true
)
public class QuestionAnswerJobMessageListener {

	private static final Logger log = LoggerFactory.getLogger(QuestionAnswerJobMessageListener.class);

	private final QuestionAnswerJobDispatchService dispatchService;
	private final AiJobDeadLetterPublisher deadLetterPublisher;
	private final ObjectMapper objectMapper;

	public QuestionAnswerJobMessageListener(
		QuestionAnswerJobDispatchService dispatchService,
		AiJobDeadLetterPublisher deadLetterPublisher,
		ObjectMapper objectMapper
	) {
		this.dispatchService = dispatchService;
		this.deadLetterPublisher = deadLetterPublisher;
		this.objectMapper = objectMapper;
	}

	@RabbitListener(queues = AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH)
	public void onMessage(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
		throws IOException {
		JsonNode root;
		try {
			root = objectMapper.readTree(message.getBody());
		}
		catch (IOException exception) {
			dlq(message, channel, deliveryTag, AiJobMessageSettlement.REASON_UNPARSEABLE_PAYLOAD);
			return;
		}
		if (root == null || root.isMissingNode() || root.isNull()) {
			dlq(message, channel, deliveryTag, AiJobMessageSettlement.REASON_UNPARSEABLE_PAYLOAD);
			return;
		}

		Optional<String> schemaViolation =
			AiJobMessageSettlement.validateSchemaVersion(readIntOrNull(root, "schemaVersion"));
		if (schemaViolation.isPresent()) {
			dlq(message, channel, deliveryTag, schemaViolation.get());
			return;
		}

		OptionalLong questionIdField = AiJobMessageSettlement.readPositiveIntegralId(root, "questionId");
		if (questionIdField.isEmpty()) {
			dlq(message, channel, deliveryTag, AiJobMessageSettlement.REASON_INVALID_PAYLOAD);
			return;
		}
		long questionId = questionIdField.getAsLong();

		try {
			QuestionAnswerJobDispatchResult result = dispatchService.dispatch(questionId);
			log.info("event=ai_job_consumed jobType=question_answer_dispatch questionId={} result={}",
				questionId, result);
			settle(result, message, channel, deliveryTag);
		}
		catch (RuntimeException failure) {
			log.error(
				"event=ai_job_dispatch_exception jobType=question_answer_dispatch questionId={} failureType={}",
				questionId, failure.getClass().getSimpleName());
			deadLetterPublisher.retryOrDeadLetter(
				channel, deliveryTag, message, AiJobMessageSettlement.REASON_DISPATCH_EXCEPTION);
		}
	}

	private void settle(QuestionAnswerJobDispatchResult result, Message message, Channel channel, long deliveryTag)
		throws IOException {
		switch (result) {
			case ENQUEUED, ALREADY_ACTIVE, ALREADY_COMPLETED, CANCELLED_OR_DELETED, DEAD ->
				channel.basicAck(deliveryTag, false);
			case SATURATED -> retry(message, channel, deliveryTag, AiJobMessageSettlement.REASON_DISPATCH_SATURATED);
			case DISABLED -> retry(message, channel, deliveryTag, AiJobMessageSettlement.REASON_DISPATCH_DISABLED);
			case INVARIANT_BREACH ->
				dlq(message, channel, deliveryTag, AiJobMessageSettlement.REASON_TICKET_NOT_FOUND);
		}
	}

	private void retry(Message message, Channel channel, long deliveryTag, String reasonCode) throws IOException {
		deadLetterPublisher.retryOrDeadLetter(channel, deliveryTag, message, reasonCode);
	}

	private void dlq(Message message, Channel channel, long deliveryTag, String reasonCode) throws IOException {
		log.error("event=ai_job_dead_lettered jobType=question_answer_dispatch reasonCode={}", reasonCode);
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
