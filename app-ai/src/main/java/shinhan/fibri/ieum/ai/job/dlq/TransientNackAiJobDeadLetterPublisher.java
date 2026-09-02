package shinhan.fibri.ieum.ai.job.dlq;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/**
 * {@link AiJobDeadLetterPublisher}의 Task 5 임시 구현.
 *
 * <p>실제 DLX republish(원본 메시지를 {@code ieum.ai.dlx}로 다시 보낸 뒤 원본을 ACK)는 Task 6이
 * 구현한다. 그 전까지는 {@code basicNack(deliveryTag, false, false)}로 처리한다 — work 큐의
 * {@code x-dead-letter-exchange}가 retry 큐를 가리키므로 이 메시지는 물리적으로는 retry 큐로 간다.
 * 즉 Task 5 단계에서 "즉시 DLQ" 조건(파싱 실패·스키마 미지원·잘못된 ID·티켓 없음)도 30초 후
 * 한 번 재시도된다 — 컨슈머의 <b>판정</b>은 이미 DLQ 로 옳게 분류되어 있으므로 Task 6이 republish
 * 구현으로 교체하기만 하면 되고, 판정 로직은 변경할 필요가 없다.
 */
@Component
public class TransientNackAiJobDeadLetterPublisher implements AiJobDeadLetterPublisher {

	private static final Logger log = LoggerFactory.getLogger(TransientNackAiJobDeadLetterPublisher.class);

	@Override
	public void deadLetter(Channel channel, long deliveryTag, Message message, String reasonCode) throws IOException {
		log.error(
			"event=ai_job_dead_lettered reasonCode={} transientHandling=nack deliveryTag={}",
			reasonCode, deliveryTag
		);
		channel.basicNack(deliveryTag, false, false);
	}
}
