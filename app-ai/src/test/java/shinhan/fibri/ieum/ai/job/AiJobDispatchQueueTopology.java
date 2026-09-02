package shinhan.fibri.ieum.ai.job;

import java.util.Map;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * work/retry/DLQ 체인을 raw {@link RabbitAdmin}으로 선언하는 테스트 전용 헬퍼. 큐 인자
 * 규칙(work 큐의 {@code x-dead-letter-*} → retry 큐, retry 큐의 TTL 만료 → 원래 exchange/routing key)은
 * app-ai 의 {@code AiJobRabbitConfiguration}·app-main 의 {@code AiJobRabbitConfig}와 동일하다
 * (spec.md §6.3).
 *
 * <p><b>큐 이름은 프로덕션 {@code AiJobTopology} 상수를 쓰지 않는다</b> — {@code AiJobRabbitContainer}는
 * JVM 안의 모든 app-ai 테스트 클래스가 공유하는 정적 싱글턴 브로커인데, {@code AiJobRabbitTopologyTest}·
 * {@code QuestionAnswerDispatchEndToEndTest}가 이미 실제 큐 이름({@code ieum.ai.question-answer.dispatch}
 * 등)을 실제 TTL(30초)로 선언해 두었다. 이 테스트들이 TTL 1초로 같은 이름을 재선언하면 브로커가
 * {@code PRECONDITION_FAILED}(406)로 채널을 끊는다 — 애초에 재시도 카운팅 메커니즘 자체(TTL+DLX+
 * x-death)는 큐 이름과 무관하므로, 완전히 격리된 테스트 전용 이름을 쓰는 것으로 해결한다. exchange는
 * (direct·durable 하나뿐이라 재선언 충돌 소지가 없으므로) 실제 {@link AiJobTopology} 상수를 그대로
 * 재사용한다.
 */
final class AiJobDispatchQueueTopology {

	private AiJobDispatchQueueTopology() {
	}

	/** {@code retryTtlMs}로 retry 큐의 TTL을 지정해서, 격리된 이름의 work/retry/DLQ 체인을 선언한다. */
	static void declare(
		RabbitAdmin admin, int retryTtlMs, String workQueue, String retryQueue, String dlq, String routingKey
	) {
		declareWorkAndRetry(admin, retryTtlMs, workQueue, retryQueue, routingKey);

		admin.declareQueue(QueueBuilder.durable(dlq).build());
		admin.declareBinding(BindingBuilder
			.bind(new Queue(dlq))
			.to(new DirectExchange(AiJobTopology.EXCHANGE_DLX))
			.with(dlq));
	}

	/**
	 * work/retry 체인만 선언하고 <b>DLQ 큐/바인딩은 일부러 선언하지 않는다</b> — 토폴로지 drift(운영
	 * 실수로 DLQ 바인딩이 빠진 상황)를 시뮬레이션해서, republish 가 unroutable 이어도 원본을 잃지
	 * 않는다는 안전장치({@code RabbitAiJobDeadLetterPublisher}의 confirm+mandatory+return listener)를
	 * 검증하는 데 쓴다. {@code EXCHANGE_DLX}는 여전히 선언한다(exchange 자체는 존재해야 mandatory
	 * 발행이 "unroutable"로 반송되지, exchange 자체가 없으면 발행이 채널 예외로 다르게 실패한다).
	 */
	static void declareWithoutDlqBinding(
		RabbitAdmin admin, int retryTtlMs, String workQueue, String retryQueue, String routingKey
	) {
		declareWorkAndRetry(admin, retryTtlMs, workQueue, retryQueue, routingKey);
	}

	private static void declareWorkAndRetry(
		RabbitAdmin admin, int retryTtlMs, String workQueue, String retryQueue, String routingKey
	) {
		admin.declareExchange(directExchange(AiJobTopology.EXCHANGE_JOBS));
		admin.declareExchange(directExchange(AiJobTopology.EXCHANGE_RETRY));
		admin.declareExchange(directExchange(AiJobTopology.EXCHANGE_DLX));

		Map<String, Object> workArgs = Map.of(
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_RETRY,
			"x-dead-letter-routing-key", retryQueue
		);
		admin.declareQueue(QueueBuilder.durable(workQueue).withArguments(workArgs).build());
		admin.declareBinding(BindingBuilder
			.bind(new Queue(workQueue))
			.to(new DirectExchange(AiJobTopology.EXCHANGE_JOBS))
			.with(routingKey));

		Map<String, Object> retryArgs = Map.of(
			"x-message-ttl", retryTtlMs,
			"x-dead-letter-exchange", AiJobTopology.EXCHANGE_JOBS,
			"x-dead-letter-routing-key", routingKey
		);
		admin.declareQueue(QueueBuilder.durable(retryQueue).withArguments(retryArgs).build());
		admin.declareBinding(BindingBuilder
			.bind(new Queue(retryQueue))
			.to(new DirectExchange(AiJobTopology.EXCHANGE_RETRY))
			.with(retryQueue));
	}

	private static DirectExchange directExchange(String name) {
		return ExchangeBuilder.directExchange(name).durable(true).build();
	}
}
