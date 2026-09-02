package shinhan.fibri.ieum.ai.job.dlq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * {@link AiJobDeadLetterPublisher}의 Task 6 구현. spec.md §6.3.
 *
 * <p>DLQ 큐는 원본 work 큐 이름 뒤에 {@code .dlq}를 붙인 이름이다({@code AiJobTopology} 명명 규칙 —
 * 예: {@code ieum.ai.question-answer.dispatch} → {@code ieum.ai.question-answer.dispatch.dlq}).
 * 실제 {@code @RabbitListener} 컨테이너({@code BlockingQueueConsumer})는 소비한 큐 이름을
 * {@link MessageProperties#setConsumerQueue(String)}으로 채워 주므로, 이 이름 규칙만으로 별도 조회
 * 없이 대상 DLQ routing key({@code ieum.ai.dlx}의 바인딩 routing key = DLQ 큐 이름)를 구할 수 있다.
 */
@Component
public class RabbitAiJobDeadLetterPublisher implements AiJobDeadLetterPublisher {

	private static final Logger log = LoggerFactory.getLogger(RabbitAiJobDeadLetterPublisher.class);

	private static final String DLQ_SUFFIX = ".dlq";

	@Override
	public void deadLetter(Channel channel, long deliveryTag, Message message, String reasonCode) throws IOException {
		republishToDlq(channel, message, reasonCode);
		channel.basicAck(deliveryTag, false);
	}

	@Override
	public void retryOrDeadLetter(Channel channel, long deliveryTag, Message message, String reasonCode)
		throws IOException {
		if (deathCount(message) >= AiJobTopology.MAX_DELIVERY_ATTEMPTS) {
			deadLetter(channel, deliveryTag, message, reasonCode);
			return;
		}
		channel.basicNack(deliveryTag, false, false);
	}

	private void republishToDlq(Channel channel, Message message, String reasonCode) throws IOException {
		MessageProperties source = message.getMessageProperties();
		String consumerQueue = source.getConsumerQueue();
		String dlqRoutingKey = consumerQueue + DLQ_SUFFIX;

		Map<String, Object> headers = new HashMap<>(source.getHeaders());
		headers.put(HEADER_DLQ_REASON, reasonCode);

		AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
			.headers(headers)
			.contentType(source.getContentType())
			.contentEncoding(source.getContentEncoding())
			.deliveryMode(MessageDeliveryMode.toInt(
				source.getDeliveryMode() == null ? MessageDeliveryMode.PERSISTENT : source.getDeliveryMode()
			))
			.messageId(source.getMessageId())
			.correlationId(source.getCorrelationId())
			.type(source.getType())
			.appId(source.getAppId())
			.build();

		log.error(
			"event=ai_job_dead_lettered reasonCode={} sourceQueue={} dlqQueue={}",
			reasonCode, consumerQueue, dlqRoutingKey
		);
		channel.basicPublish(AiJobTopology.EXCHANGE_DLX, dlqRoutingKey, properties, message.getBody());
	}

	private static long deathCount(Message message) {
		List<Map<String, ?>> xDeath = message.getMessageProperties().getXDeathHeader();
		if (xDeath == null || xDeath.isEmpty()) {
			return 0;
		}
		Object count = xDeath.get(0).get("count");
		return count instanceof Number number ? number.longValue() : 0;
	}
}
