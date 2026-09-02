package shinhan.fibri.ieum.ai.job.dlq;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;

/**
 * {@link shinhan.fibri.ieum.ai.job.AiJobMessageSettlement#DLQ} 판정을 실제로 처리하는 경계.
 * spec.md §6.3: 재시도로 고칠 수 없는 실패는 work 큐를 NACK 하는 것만으로는 부족하다 — work 큐의
 * {@code x-dead-letter-exchange}가 이미 retry 큐를 가리키므로, 진짜 DLQ 로 보내려면 원본 메시지를
 * {@code ieum.ai.dlx}의 DLQ routing key 로 republish 한 뒤 원본을 ACK 해야 한다.
 *
 * <p><b>Task 5 범위 밖:</b> 이 인터페이스의 실제 republish 구현은 Task 6(재시도/DLQ)이 붙인다.
 * 이 태스크(Task 5)의 구현체는 {@code channel.basicNack(deliveryTag, false, false)}로 임시 처리한다
 * — 물리적으로는 {@link shinhan.fibri.ieum.ai.job.AiJobMessageSettlement#RETRY}와 같은 동작이지만,
 * 컨슈머의 정산 판정 자체는 이미 {@code DLQ}로 구분되어 있다(브리프 "구현 단계" 4번, 테스트로 검증됨).
 */
public interface AiJobDeadLetterPublisher {

	/**
	 * @param channel     원본 메시지를 받은 채널
	 * @param deliveryTag 원본 메시지의 delivery tag
	 * @param message     원본 AMQP 메시지 (재발행 시 body/헤더를 그대로 쓴다)
	 * @param reasonCode  {@code AiJobMessageSettlement}의 사유 코드 (예: {@code unparseable_payload})
	 */
	void deadLetter(Channel channel, long deliveryTag, Message message, String reasonCode) throws IOException;
}
