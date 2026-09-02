package shinhan.fibri.ieum.ai.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.ai.job.dlq.AiJobDeadLetterPublisher;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskLane;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskSubmission;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * {@code ai.accepted-answer.ingest} 큐 컨슈머. spec.md §8.2 "채택 답변 지식화도 동일하다".
 *
 * <p><b>ACK 시점 계약</b>은 {@link QuestionAnswerJobMessageListener}와 같다(spec.md §8.3) —
 * lane 제출 성공 시점에 ACK 한다. 상태 검증(멱등성)은 워커의 {@code claimByAnswerId(answerId)}가
 * lease/attempt fencing 으로 담당하므로, 이 컨슈머는 DB 조회 없이 lane 에 넣기만 한다.
 */
@Component
public class AcceptedAnswerKnowledgeMessageListener {

	private static final Logger log = LoggerFactory.getLogger(AcceptedAnswerKnowledgeMessageListener.class);

	private final AcceptedAnswerKnowledgeTaskLane lane;
	private final AiJobDeadLetterPublisher deadLetterPublisher;
	private final ObjectMapper objectMapper;

	public AcceptedAnswerKnowledgeMessageListener(
		AcceptedAnswerKnowledgeTaskLane lane,
		AiJobDeadLetterPublisher deadLetterPublisher,
		ObjectMapper objectMapper
	) {
		this.lane = lane;
		this.deadLetterPublisher = deadLetterPublisher;
		this.objectMapper = objectMapper;
	}

	@RabbitListener(queues = AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST)
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

		long answerId = root.path("answerId").asLong(0);
		if (answerId <= 0) {
			dlq(message, channel, deliveryTag, AiJobMessageSettlement.REASON_INVALID_PAYLOAD);
			return;
		}

		AcceptedAnswerKnowledgeTaskSubmission submission = lane.submit(answerId);
		log.info("event=ai_job_consumed jobType=accepted_answer_knowledge_ingest answerId={} result={}",
			answerId, submission);
		settle(submission, message, channel, deliveryTag);
	}

	private void settle(AcceptedAnswerKnowledgeTaskSubmission submission, Message message, Channel channel,
		long deliveryTag) throws IOException {
		switch (submission) {
			case ENQUEUED, ALREADY_ACTIVE -> channel.basicAck(deliveryTag, false);
			case SATURATED -> retry(message, channel, deliveryTag, AiJobMessageSettlement.REASON_DISPATCH_SATURATED);
			case DISABLED -> retry(message, channel, deliveryTag, AiJobMessageSettlement.REASON_DISPATCH_DISABLED);
		}
	}

	private void retry(Message message, Channel channel, long deliveryTag, String reasonCode) throws IOException {
		deadLetterPublisher.retryOrDeadLetter(channel, deliveryTag, message, reasonCode);
	}

	private void dlq(Message message, Channel channel, long deliveryTag, String reasonCode) throws IOException {
		log.error("event=ai_job_dead_lettered jobType=accepted_answer_knowledge_ingest reasonCode={}", reasonCode);
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
