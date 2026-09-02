package shinhan.fibri.ieum.ai.job;

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
 * NACK 하는 유일한 경우는 wake 자체가 실패했을 때(lane 포화, 기능 비활성)다.
 *
 * <p>멱등성은 {@code jobId}가 아니라 {@code ai_question_tasks}의 DB 상태 머신이 보장한다
 * (spec.md §8.2) — {@link QuestionAnswerJobDispatchService#dispatch(long)}가 이미 이 판단을 한다.
 */
@Component
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

		long questionId = root.path("questionId").asLong(0);
		if (questionId <= 0) {
			dlq(message, channel, deliveryTag, AiJobMessageSettlement.REASON_INVALID_PAYLOAD);
			return;
		}

		QuestionAnswerJobDispatchResult result = dispatchService.dispatch(questionId);
		log.info("event=ai_job_consumed jobType=question_answer_dispatch questionId={} result={}",
			questionId, result);
		settle(result, message, channel, deliveryTag);
	}

	private void settle(QuestionAnswerJobDispatchResult result, Message message, Channel channel, long deliveryTag)
		throws IOException {
		switch (result) {
			case ENQUEUED, ALREADY_ACTIVE, ALREADY_COMPLETED, CANCELLED_OR_DELETED, DEAD ->
				channel.basicAck(deliveryTag, false);
			case SATURATED, DISABLED -> channel.basicNack(deliveryTag, false, false);
			case INVARIANT_BREACH ->
				dlq(message, channel, deliveryTag, AiJobMessageSettlement.REASON_TICKET_NOT_FOUND);
		}
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
