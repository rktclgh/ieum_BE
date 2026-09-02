package shinhan.fibri.ieum.testsupport;

import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * app-ai 의 RabbitMQ 통합 테스트가 공유하는 {@link AiJobRabbitContainer}는 클래스 로딩 시 한 번 뜨고
 * JVM 안의 <b>모든</b> 테스트 클래스가 재사용하는 정적 싱글턴이다. 한 테스트가 남긴 메시지가 다른
 * 테스트의 첫 소비 메시지로 잘못 집히는 사고(Task 5의 {@code QuestionAnswerDispatchEndToEndTest}가
 * 겪은 것과 같은 문제)를 막기 위해, 토폴로지를 선언한 뒤 실제 검증을 시작하기 전에 이 헬퍼로 관련
 * 큐를 전부 비운다.
 *
 * <p>사용처: {@code @BeforeEach}에서 토폴로지 선언 직후 {@link #purgeAll(RabbitAdmin)}(전체 9개 큐)
 * 또는 {@link #purge(RabbitAdmin, String...)}(테스트가 실제로 쓰는 큐만)를 호출한다. 큐가 아직
 * 선언되지 않은 상태에서 purge 하면 브로커가 예외를 던지므로, 반드시 선언 이후에 호출한다.
 */
public final class AiJobQueuePurger {

	private static final List<String> ALL_QUEUES = List.of(
		AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH,
		AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY,
		AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ,
		AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST,
		AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY,
		AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_DLQ,
		AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED,
		AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY,
		AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ
	);

	private AiJobQueuePurger() {
	}

	/** {@code AiJobTopology}가 정의하는 전체 9개 큐를 비운다. */
	public static void purgeAll(RabbitAdmin admin) {
		for (String queue : ALL_QUEUES) {
			admin.purgeQueue(queue, false);
		}
	}

	/** 테스트가 실제로 선언·사용하는 큐만 골라서 비운다. */
	public static void purge(RabbitAdmin admin, String... queues) {
		for (String queue : queues) {
			admin.purgeQueue(queue, false);
		}
	}
}
