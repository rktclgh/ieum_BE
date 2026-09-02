package shinhan.fibri.ieum.ai.question.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.common.ai.job.AiJobType;
import shinhan.fibri.ieum.common.ai.job.dto.QuestionAnswerCompletedMessage;

/**
 * {@link QuestionCompletionCallbackClient}의 RabbitMQ 구현. spec.md §8.5, 브리프 "구현 단계" 2번.
 *
 * <p>기존 인터페이스를 <b>그대로</b> 구현한다. 이러면 {@link QuestionCompletionCallbackLane}, in-flight
 * dedup, {@code questionCompletionCallbackExecutor}를 전혀 건드리지 않고 전송 수단만 HTTP 에서
 * RabbitMQ 로 교체된다.
 *
 * <p>{@code AiJobOutboxRelay}(app-main)와 달리 이쪽은 outbox row 가 없다(spec.md §5.4 — {@code
 * ai_question_tasks} 자체가 outbox 다). 그래서 {@code jobId}는 호출마다 새로 발급한다 — 멱추적용일 뿐,
 * 멱등 판정은 app-main 소비 측의 알림 유니크 제약(§8.4)이 한다.
 *
 * <p>confirm 대기 동안 DB 커넥션이나 락을 잡지 않는다 — 이 클래스는 그 자체로 트랜잭션 밖에서
 * 호출된다({@code QuestionCompletionCallbackLane}이 별도 executor 에서 실행한다).
 */
public class RabbitQuestionCompletionCallbackClient implements QuestionCompletionCallbackClient {

	private static final Logger log = LoggerFactory.getLogger(RabbitQuestionCompletionCallbackClient.class);

	/** AMQP {@code app_id}. spec.md §6.5. */
	static final String APP_ID = "ieum-app-ai";

	private final RabbitTemplate rabbitTemplate;
	private final ObjectMapper objectMapper;
	private final Duration confirmTimeout;

	public RabbitQuestionCompletionCallbackClient(
		RabbitTemplate rabbitTemplate,
		ObjectMapper objectMapper,
		Duration confirmTimeout
	) {
		this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.confirmTimeout = Objects.requireNonNull(confirmTimeout, "confirmTimeout must not be null");
		if (confirmTimeout.isZero() || confirmTimeout.isNegative()) {
			throw new IllegalArgumentException("confirmTimeout must be positive");
		}
	}

	@Override
	public CallbackHttpResult deliver(long questionId, long answerId) {
		validatePositive(questionId, "questionId");
		validatePositive(answerId, "answerId");

		String jobId = UUID.randomUUID().toString();
		CorrelationData correlation = new CorrelationData(jobId);
		try {
			rabbitTemplate.send(
				AiJobTopology.EXCHANGE_RESULTS,
				AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED,
				toMessage(jobId, questionId, answerId),
				correlation
			);

			CorrelationData.Confirm confirm = correlation.getFuture()
				.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
			ReturnedMessage returned = correlation.getReturned();
			if (returned != null) {
				log.warn(
					"event=question_completion_publish_returned questionId={} answerId={} jobId={}",
					questionId, answerId, jobId
				);
				return CallbackHttpResult.FAILED;
			}
			if (confirm == null || !confirm.ack()) {
				log.warn(
					"event=question_completion_publish_nack questionId={} answerId={} jobId={}",
					questionId, answerId, jobId
				);
				return CallbackHttpResult.FAILED;
			}
			return CallbackHttpResult.DELIVERED;
		}
		catch (TimeoutException timeout) {
			// 발행 자체는 브로커에 닿았을 수 있다 → 재시도(relay) 시 중복 발행 가능. app-main 의
			// 알림 유니크 제약이 흡수한다(spec.md §8.4).
			log.warn(
				"event=question_completion_publish_confirm_timeout questionId={} answerId={} jobId={}",
				questionId, answerId, jobId
			);
			return CallbackHttpResult.FAILED;
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return CallbackHttpResult.FAILED;
		}
		catch (ExecutionException | RuntimeException failure) {
			log.warn(
				"event=question_completion_publish_failed questionId={} answerId={} jobId={} failureType={}",
				questionId, answerId, jobId, failure.getClass().getSimpleName()
			);
			return CallbackHttpResult.FAILED;
		}
	}

	private Message toMessage(String jobId, long questionId, long answerId) {
		QuestionAnswerCompletedMessage payload = new QuestionAnswerCompletedMessage(
			AiJobTopology.SCHEMA_VERSION,
			jobId,
			AiJobType.QUESTION_ANSWER_COMPLETED,
			questionId,
			answerId,
			Instant.now().toString()
		);
		byte[] body;
		try {
			body = objectMapper.writeValueAsBytes(payload);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
			// payload 는 record 이고 필드가 전부 원시/문자열이다 — 직렬화 실패는 사실상 불가능하다.
			throw new IllegalStateException("Failed to serialize question completion message", exception);
		}

		MessageProperties properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setContentEncoding(StandardCharsets.UTF_8.name());
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(jobId);
		properties.setCorrelationId(jobId);
		properties.setType(AiJobType.QUESTION_ANSWER_COMPLETED.value());
		properties.setAppId(APP_ID);
		properties.setHeader("x-ieum-schema-version", AiJobTopology.SCHEMA_VERSION);
		return new Message(body, properties);
	}

	private static void validatePositive(long value, String field) {
		if (value <= 0) {
			throw new IllegalArgumentException(field + " must be positive");
		}
	}
}
