package shinhan.fibri.ieum.main.ai.result.dlq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ReturnListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * {@link AiResultDeadLetterPublisher}의 app-main 구현. spec.md §6.3.
 *
 * <p>app-ai 의 {@code RabbitAiJobDeadLetterPublisher}를 그대로 미러링한다 — 두 앱이 서로 import
 * 할 수 없어(별개 Gradle 모듈) 로직을 복제했다. DLQ 큐 이름은 원본 work 큐 이름 뒤에 {@code .dlq}를
 * 붙인 이름이다({@code AiJobTopology} 명명 규칙 — {@code ieum.main.question-answer.completed} →
 * {@code ieum.main.question-answer.completed.dlq}). {@code @RabbitListener} 컨테이너가 소비한 큐
 * 이름을 {@link MessageProperties#setConsumerQueue(String)}으로 채워주므로, 이 규칙만으로 별도
 * 조회 없이 대상 DLQ routing key 를 구한다.
 *
 * <p><b>republish 확인 없이 ACK 하면 메시지가 조용히 사라질 수 있다.</b> {@code channel.basicPublish}는
 * fire-and-forget 이라 라우팅 불가(토폴로지 drift 등)여도 예외를 던지지 않는다. 여기서 직접
 * {@code confirmSelect}+{@code mandatory=true}+return listener 로 republish 를 검증한 뒤에만
 * 원본을 ACK 한다. 검증에 실패하면(반송·미확인·타임아웃) 원본은 <b>ACK 하지 않고</b>
 * {@code NACK(requeue=false)} 한다 — retry 큐를 거쳐 다시 배달되게 해서 다음 배달에서 republish 를
 * 재시도할 기회를 준다.
 */
@Component
public class RabbitAiResultDeadLetterPublisher implements AiResultDeadLetterPublisher {

	private static final Logger log = LoggerFactory.getLogger(RabbitAiResultDeadLetterPublisher.class);

	private static final String DLQ_SUFFIX = ".dlq";
	private static final long CONFIRM_TIMEOUT_MS = 5_000L;

	@Override
	public void deadLetter(Channel channel, long deliveryTag, Message message, String reasonCode) throws IOException {
		if (republishToDlqConfirmed(channel, message, reasonCode)) {
			channel.basicAck(deliveryTag, false);
			return;
		}
		log.error(
			"event=ai_result_dead_letter_republish_unconfirmed reasonCode={} deliveryTag={} — NACKing original "
				+ "instead of ACKing, to avoid losing it; it will cycle back through the retry queue",
			reasonCode, deliveryTag
		);
		channel.basicNack(deliveryTag, false, false);
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

	private boolean republishToDlqConfirmed(Channel channel, Message message, String reasonCode) throws IOException {
		MessageProperties source = message.getMessageProperties();
		String consumerQueue = Objects.requireNonNull(
			source.getConsumerQueue(),
			"consumerQueue must be set — real @RabbitListener deliveries always populate it; without it "
				+ "there is no safe way to derive the target DLQ routing key"
		);
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

		ensureConfirmMode(channel);
		AtomicBoolean returned = new AtomicBoolean(false);
		ReturnListener returnListener = channel.addReturnListener(ret -> returned.set(true));
		try {
			log.error(
				"event=ai_result_dead_lettered reasonCode={} sourceQueue={} dlqQueue={}",
				reasonCode, consumerQueue, dlqRoutingKey
			);
			channel.basicPublish(AiJobTopology.EXCHANGE_DLX, dlqRoutingKey, true, properties, message.getBody());

			boolean confirmed;
			try {
				confirmed = channel.waitForConfirms(CONFIRM_TIMEOUT_MS);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				confirmed = false;
			}
			catch (TimeoutException timedOut) {
				confirmed = false;
			}
			return confirmed && !returned.get();
		}
		finally {
			channel.removeReturnListener(returnListener);
		}
	}

	/** 이미 confirm 모드인 채널에 다시 {@code confirmSelect}를 거는 것을 피한다. */
	private static void ensureConfirmMode(Channel channel) throws IOException {
		if (channel.getNextPublishSeqNo() == 0) {
			channel.confirmSelect();
		}
	}

	/** work 큐(reason={@code rejected})와 retry 큐(reason={@code expired})가 번갈아 죽으므로 첫 엔트리가 최신 죽음이다. */
	private static long deathCount(Message message) {
		List<Map<String, ?>> xDeath = message.getMessageProperties().getXDeathHeader();
		if (xDeath == null || xDeath.isEmpty()) {
			return 0;
		}
		Object count = xDeath.get(0).get("count");
		return count instanceof Number number ? number.longValue() : 0;
	}
}
