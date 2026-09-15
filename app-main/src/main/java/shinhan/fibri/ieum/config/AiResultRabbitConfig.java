package shinhan.fibri.ieum.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * 완료 결과(콜백) 토폴로지 + 공유 exchange(retry/dlx). spec.md §6.2, §11.5.
 *
 * <p><b>왜 {@link AiJobRabbitConfig}(디스패치 전용)와 분리했는가</b>: app-ai 는 완료 통보를 브로커로
 * 발행한다(`APP_AI_QUESTION_CALLBACK_TRANSPORT=rabbitmq`, `APP_AI_COMPLETION_RELAY_ENABLED=true`).
 * 그 메시지를 받는 app-main 결과 컨슈머는 app-main 의 디스패치 발행 스위치
 * (`APP_AI_DISPATCH_TRANSPORT`)와 무관하게 항상 살아 있어야 한다 — 그 스위치를 {@code http}로 롤백해도
 * app-ai 는 여전히 브로커로 완료를 발행하기 때문이다. 전체 토폴로지를 디스패치 스위치 하나에만 묶어 두면
 * 롤백 중 완료 메시지가 소비자 없이 큐에 쌓이기만 한다.
 *
 * <p>이 클래스는 {@code app.ai.dispatch.transport=rabbitmq} 또는 {@code app.ai.result.consumer.enabled}
 * (기본값 {@code true} — app-ai 디스패치 컨슈머가 항상 켜져 있는 것과 같은 패턴이다. app-main
 * {@code shinhan.fibri.ieum.main.ai.result.QuestionAnswerCompletedMessageListener}도 같은 이름의
 * 프로퍼티로 게이트된다) 중 <b>하나라도</b> 참이면 활성화된다({@link RabbitTopologyRequiredCondition}).
 * 예전에는 첫 조건이 {@code app.ai.outbox.enabled=true}였다 — app-main 전체의 전송 스위치를
 * {@code app.ai.dispatch.transport} 하나로 모으면서 대체했다(application.properties 참고).
 * {@code aiRetryExchange}/{@code aiDlxExchange}는 디스패치 큐({@link AiJobRabbitConfig})도 참조하는
 * <b>공유</b> exchange라서, 결과 컨슈머를 명시적으로 꺼도(outbox 만 켜진 경우) 반드시 여기서 선언돼야
 * 디스패치 쪽 바인딩이 깨지지 않는다.
 */
@Configuration
@Conditional(AiResultRabbitConfig.RabbitTopologyRequiredCondition.class)
public class AiResultRabbitConfig {

	private static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
	private static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";
	private static final String ARG_MESSAGE_TTL = "x-message-ttl";

	// --- exchange (aiRetryExchange/aiDlxExchange 는 AiJobRabbitConfig 의 디스패치 큐도 참조한다) ---

	@Bean
	DirectExchange aiResultsExchange() {
		return durableDirect(AiJobTopology.EXCHANGE_RESULTS);
	}

	@Bean
	DirectExchange aiRetryExchange() {
		return durableDirect(AiJobTopology.EXCHANGE_RETRY);
	}

	@Bean
	DirectExchange aiDlxExchange() {
		return durableDirect(AiJobTopology.EXCHANGE_DLX);
	}

	// --- queue: question-answer completed (result, app-main 이 소비) ---

	@Bean
	Queue mainQuestionAnswerCompletedQueue() {
		return workQueue(
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED,
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY
		);
	}

	@Bean
	Queue mainQuestionAnswerCompletedRetryQueue() {
		return retryQueue(
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY,
			AiJobTopology.EXCHANGE_RESULTS,
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED
		);
	}

	@Bean
	Queue mainQuestionAnswerCompletedDlq() {
		return durableQueue(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ);
	}

	// --- binding ---

	@Bean
	Binding mainQuestionAnswerCompletedBinding() {
		return bind(mainQuestionAnswerCompletedQueue(), aiResultsExchange(),
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED);
	}

	@Bean
	Binding mainQuestionAnswerCompletedRetryBinding() {
		return bind(mainQuestionAnswerCompletedRetryQueue(), aiRetryExchange(),
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY);
	}

	@Bean
	Binding mainQuestionAnswerCompletedDlqBinding() {
		return bind(mainQuestionAnswerCompletedDlq(), aiDlxExchange(),
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ);
	}

	// --- helpers (AiJobRabbitConfig 와 동일한 헬퍼를 각자 갖는다 — 두 설정 클래스의 활성 조건이
	// 달라서 하나가 없어도 다른 하나가 완결적으로 동작해야 한다) ---

	private static DirectExchange durableDirect(String name) {
		return ExchangeBuilder.directExchange(name).durable(true).build();
	}

	private static Queue durableQueue(String name) {
		return QueueBuilder.durable(name).build();
	}

	/** 소비 실패 시 retry 큐로 dead-letter 되는 작업 큐(spec.md §6.3). */
	private static Queue workQueue(String name, String retryQueueName) {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(ARG_DEAD_LETTER_EXCHANGE, AiJobTopology.EXCHANGE_RETRY);
		arguments.put(ARG_DEAD_LETTER_ROUTING_KEY, retryQueueName);
		return QueueBuilder.durable(name).withArguments(arguments).build();
	}

	/** TTL 만료 후 원래 exchange/routing key 로 되돌려보내는 대기 큐(spec.md §6.3). */
	private static Queue retryQueue(String name, String targetExchange, String targetRoutingKey) {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(ARG_MESSAGE_TTL, AiJobTopology.RETRY_TTL_MS);
		arguments.put(ARG_DEAD_LETTER_EXCHANGE, targetExchange);
		arguments.put(ARG_DEAD_LETTER_ROUTING_KEY, targetRoutingKey);
		return QueueBuilder.durable(name).withArguments(arguments).build();
	}

	private static Binding bind(Queue queue, DirectExchange exchange, String routingKey) {
		return BindingBuilder.bind(queue).to(exchange).with(routingKey);
	}

	/**
	 * {@code app.ai.dispatch.transport=rabbitmq} 이거나 {@code app.ai.result.consumer.enabled}가
	 * (기본값 포함) 참이면 활성. 두 번째 프로퍼티 이름은 {@code QuestionAnswerCompletedMessageListener}의
	 * 게이트와 반드시 동일해야 한다 — 여기 어긋나면 컨슈머가 소비할 큐가 선언되지 않는 사고가 난다.
	 *
	 * <p><b>{@code PARSE_CONFIGURATION} 이어야 한다 — {@code REGISTER_BEAN} 이 아니다.</b> 이 조건은
	 * {@code @Configuration} 클래스 전체({@link AiResultRabbitConfig})에 붙어 있다. Spring 은
	 * {@code REGISTER_BEAN} 단계 조건을 클래스가 아니라 <b>개별 {@code @Bean} 메서드</b>의 조건으로
	 * 재평가한다({@code ConfigurationClassBeanDefinitionReader#loadBeanDefinitionsForBeanMethod}가
	 * 메서드 자체의 {@code AnnotatedTypeMetadata}만 보고, 클래스 레벨 {@code @Conditional}은 그
	 * 메타데이터에 없다) — 그래서 {@code REGISTER_BEAN}으로 두면 이 조건이 사실상 한 번도 평가되지
	 * 않아 프로퍼티 값과 무관하게 모든 빈이 등록돼 버린다(실측 확인).
	 * 이 조건은 다른 빈의 존재 여부를 보지 않고 프로퍼티 값만 보므로, 클래스 파싱 시점에 평가되는
	 * {@code PARSE_CONFIGURATION}이 정확하고 유일하게 맞는 선택이다.
	 */
	static class RabbitTopologyRequiredCondition extends AnyNestedCondition {

		RabbitTopologyRequiredCondition() {
			super(ConfigurationPhase.PARSE_CONFIGURATION);
		}

		@ConditionalOnProperty(name = "app.ai.dispatch.transport", havingValue = "rabbitmq")
		static class DispatchTransportIsRabbitmq {
		}

		@ConditionalOnProperty(name = "app.ai.result.consumer.enabled", havingValue = "true", matchIfMissing = true)
		static class ResultConsumerEnabled {
		}
	}
}
