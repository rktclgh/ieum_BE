package shinhan.fibri.ieum.ai.job.dlq;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;

/**
 * {@link shinhan.fibri.ieum.ai.job.AiJobMessageSettlement#DLQ} 판정과, {@code RETRY} 판정이 재시도
 * 상한에 도달한 경우를 실제로 처리하는 경계. spec.md §6.3.
 *
 * <p><b>왜 NACK만으로는 DLQ에 못 가는가</b>: work 큐의 {@code x-dead-letter-exchange}가 이미 retry
 * 큐를 가리키므로({@code ieum.ai.retry}), {@code channel.basicNack(tag, false, false)}는 항상 retry
 * 큐로 간다. 진짜 DLQ({@code ieum.ai.dlx})로 보내는 유일한 방법은 원본 메시지를 그대로
 * {@code ieum.ai.dlx}의 DLQ routing key 로 republish 한 뒤 원본을 ACK 하는 것이다 — republish 가
 * 새 메시지를 broker 에 심고, ACK 가 work 큐에서 원본을 지운다. 이 두 단계 사이에 컨슈머가 죽으면
 * 원본이 work 큐에 그대로 남아 재배달될 뿐이므로(at-least-once), 메시지 유실은 없다.
 */
public interface AiJobDeadLetterPublisher {

	/** DLQ 로 보낸 이유를 남기는 헤더. spec.md §6.3. */
	String HEADER_DLQ_REASON = "x-ieum-dlq-reason";

	/**
	 * 재시도로 고칠 수 없는 조건(파싱 실패, 스키마 미지원, 잘못된 ID, 티켓 없음)을 <b>즉시</b> DLQ 로
	 * 보낸다 — {@code x-death} 카운트를 보지 않는다. 원본을 {@code ieum.ai.dlx}의 해당 DLQ routing
	 * key 로 republish 하고 {@link #HEADER_DLQ_REASON} 헤더를 붙인 뒤 원본을 ACK 한다.
	 *
	 * @param channel     원본 메시지를 받은 채널
	 * @param deliveryTag 원본 메시지의 delivery tag
	 * @param message     원본 AMQP 메시지 (재발행 시 body/헤더를 그대로 쓴다)
	 * @param reasonCode  {@code AiJobMessageSettlement}의 사유 코드 (예: {@code unparseable_payload})
	 */
	void deadLetter(Channel channel, long deliveryTag, Message message, String reasonCode) throws IOException;

	/**
	 * 일시적 실패({@code RETRY} 판정)를 처리한다. {@code message.getMessageProperties().getXDeathHeader()}의
	 * 첫 엔트리 {@code count}(헤더 자체가 없으면 0)를 읽어, {@code AiJobTopology.MAX_DELIVERY_ATTEMPTS}에
	 * 도달했으면 {@link #deadLetter} 와 동일하게 DLQ 경로로 전환하고, 아직이면
	 * {@code channel.basicNack(deliveryTag, false, false)} 로 retry 큐(TTL)를 통해 재시도한다.
	 * {@code requeue=true}는 절대 쓰지 않는다.
	 *
	 * @param channel     원본 메시지를 받은 채널
	 * @param deliveryTag 원본 메시지의 delivery tag
	 * @param message     원본 AMQP 메시지 ({@code x-death} 헤더 포함)
	 * @param reasonCode  상한 도달 시 DLQ 로 보낼 때 붙일 사유 코드 (예: {@code dispatch_saturated})
	 */
	void retryOrDeadLetter(Channel channel, long deliveryTag, Message message, String reasonCode) throws IOException;
}
