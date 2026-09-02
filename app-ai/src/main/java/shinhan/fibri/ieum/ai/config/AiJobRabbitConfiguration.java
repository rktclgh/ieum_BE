package shinhan.fibri.ieum.ai.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * app-ai 가 선언하는 AI job 큐 토폴로지. spec.md §3, §6.2, 브리프 "구현 단계" 2번.
 *
 * <p><b>app-main 의 {@code AiJobRabbitConfig}와 완전히 동일한 인자로 전체 토폴로지를 선언한다</b> —
 * app-ai 가 소비하지 않는 큐({@code ieum.main.question-answer.completed})까지 포함해서. 두 앱이
 * 같은 {@link AiJobTopology} 상수로 같은 인자를 선언하므로, 어느 쪽이 먼저 뜨든 결과가 같고
 * {@code PRECONDITION_FAILED}가 구조적으로 나올 수 없다(spec.md §9 "큐 인자 불일치").
 *
 * <p>manual ACK·prefetch=1·concurrency=1·max-concurrency=1 은 {@code application.properties}의
 * {@code spring.rabbitmq.listener.simple.*}(spec.md §11.4)로 준다 — Spring Boot 가 기본
 * {@code SimpleRabbitListenerContainerFactory} 빈에 그대로 반영하므로 별도 빈 선언이 필요 없다.
 *
 * <p>이 컨슈머는 <b>항상 켜 둔다</b>(spec.md §11.5) — {@code app.ai.dispatch.transport}가
 * {@code rabbitmq}(기본값)일 때만이 아니라 아예 값이 없어도({@code matchIfMissing=true}) 켜진다.
 * app-main 만 HTTP/rabbitmq 를 오간다.
 */
@Configuration
@ConditionalOnProperty(
	prefix = "app.ai.dispatch", name = "transport", havingValue = "rabbitmq", matchIfMissing = true
)
public class AiJobRabbitConfiguration {

	private static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
	private static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";
	private static final String ARG_MESSAGE_TTL = "x-message-ttl";

	// --- exchange ---

	@Bean
	DirectExchange aiJobsExchange() {
		return durableDirect(AiJobTopology.EXCHANGE_JOBS);
	}

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

	// --- queue: question-answer dispatch (app-ai 가 소비) ---

	@Bean
	Queue aiQuestionAnswerDispatchQueue() {
		return workQueue(
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH,
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY
		);
	}

	@Bean
	Queue aiQuestionAnswerDispatchRetryQueue() {
		return retryQueue(
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY,
			AiJobTopology.EXCHANGE_JOBS,
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH
		);
	}

	@Bean
	Queue aiQuestionAnswerDispatchDlq() {
		return durableQueue(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ);
	}

	// --- queue: accepted-answer knowledge ingest (app-ai 가 소비) ---

	@Bean
	Queue aiAcceptedAnswerIngestQueue() {
		return workQueue(
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST,
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY
		);
	}

	@Bean
	Queue aiAcceptedAnswerIngestRetryQueue() {
		return retryQueue(
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY,
			AiJobTopology.EXCHANGE_JOBS,
			AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST
		);
	}

	@Bean
	Queue aiAcceptedAnswerIngestDlq() {
		return durableQueue(AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_DLQ);
	}

	// --- queue: question-answer completed (result, app-main 이 소비 — app-ai 는 발행만) ---

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
	Binding aiQuestionAnswerDispatchBinding() {
		return bind(aiQuestionAnswerDispatchQueue(), aiJobsExchange(),
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH);
	}

	@Bean
	Binding aiAcceptedAnswerIngestBinding() {
		return bind(aiAcceptedAnswerIngestQueue(), aiJobsExchange(),
			AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST);
	}

	@Bean
	Binding mainQuestionAnswerCompletedBinding() {
		return bind(mainQuestionAnswerCompletedQueue(), aiResultsExchange(),
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED);
	}

	@Bean
	Binding aiQuestionAnswerDispatchRetryBinding() {
		return bind(aiQuestionAnswerDispatchRetryQueue(), aiRetryExchange(),
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY);
	}

	@Bean
	Binding aiAcceptedAnswerIngestRetryBinding() {
		return bind(aiAcceptedAnswerIngestRetryQueue(), aiRetryExchange(),
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY);
	}

	@Bean
	Binding mainQuestionAnswerCompletedRetryBinding() {
		return bind(mainQuestionAnswerCompletedRetryQueue(), aiRetryExchange(),
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY);
	}

	@Bean
	Binding aiQuestionAnswerDispatchDlqBinding() {
		return bind(aiQuestionAnswerDispatchDlq(), aiDlxExchange(),
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ);
	}

	@Bean
	Binding aiAcceptedAnswerIngestDlqBinding() {
		return bind(aiAcceptedAnswerIngestDlq(), aiDlxExchange(),
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_DLQ);
	}

	@Bean
	Binding mainQuestionAnswerCompletedDlqBinding() {
		return bind(mainQuestionAnswerCompletedDlq(), aiDlxExchange(),
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ);
	}

	// --- helpers ---

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
}
