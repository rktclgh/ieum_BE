package shinhan.fibri.ieum.main.ai.result.dlq;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;

/**
 * app-main 의 완료 결과 컨슈머({@code QuestionAnswerCompletedMessageListener})가 쓰는 DLQ/재시도
 * 경계. spec.md §6.3, §8.4.
 *
 * <p>app-ai 의 {@code shinhan.fibri.ieum.ai.job.dlq.AiJobDeadLetterPublisher}와 <b>의도적으로
 * 동일한 계약</b>이다 — 두 앱은 서로 import 하지 않으므로(별개 Gradle 모듈, 서로 의존하지 않음)
 * 여기서 그대로 다시 선언한다. 왜 NACK 만으로는 DLQ 에 못 가는지, republish 를 confirm+mandatory
 * 로 검증해야 하는 이유는 app-ai 쪽 동일 클래스의 Javadoc 을 참고한다 — 로직은 완전히 같다.
 */
public interface AiResultDeadLetterPublisher {

	/** DLQ 로 보낸 이유를 남기는 헤더. spec.md §6.3. */
	String HEADER_DLQ_REASON = "x-ieum-dlq-reason";

	/**
	 * 재시도로 고칠 수 없는 조건(파싱 실패, 스키마 미지원, 잘못된 ID, 티켓 없음, 완료 상태 충돌)을
	 * <b>즉시</b> DLQ 로 보낸다 — {@code x-death} 카운트를 보지 않는다.
	 *
	 * @param channel     원본 메시지를 받은 채널
	 * @param deliveryTag 원본 메시지의 delivery tag
	 * @param message     원본 AMQP 메시지 (재발행 시 body/헤더를 그대로 쓴다)
	 * @param reasonCode  사유 코드 (예: {@code ticket_not_found})
	 */
	void deadLetter(Channel channel, long deliveryTag, Message message, String reasonCode) throws IOException;

	/**
	 * 일시적 실패(DB transient 오류)를 처리한다. {@code x-death} 카운트가
	 * {@code AiJobTopology.MAX_DELIVERY_ATTEMPTS}에 도달했으면 {@link #deadLetter}와 동일하게 DLQ
	 * 경로로 전환하고, 아직이면 {@code channel.basicNack(deliveryTag, false, false)}로 retry
	 * 큐(TTL)를 통해 재시도한다. {@code requeue=true}는 절대 쓰지 않는다.
	 *
	 * @param channel     원본 메시지를 받은 채널
	 * @param deliveryTag 원본 메시지의 delivery tag
	 * @param message     원본 AMQP 메시지 ({@code x-death} 헤더 포함)
	 * @param reasonCode  상한 도달 시 DLQ 로 보낼 때 붙일 사유 코드 (예: {@code transient_error})
	 */
	void retryOrDeadLetter(Channel channel, long deliveryTag, Message message, String reasonCode) throws IOException;
}
