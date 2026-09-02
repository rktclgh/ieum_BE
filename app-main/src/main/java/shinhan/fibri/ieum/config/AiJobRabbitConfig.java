package shinhan.fibri.ieum.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.outbox.service.AiJobOutboxProperties;

/**
 * AI job 큐 토폴로지 + 발행용 {@link RabbitTemplate}. spec.md §6.2, §7.1, §11.4.
 *
 * <p><b>전체 토폴로지를 선언한다</b> — app-main 이 소비하지 않는 큐까지 포함해서. app-ai 도 같은
 * {@link AiJobTopology} 상수로 같은 인자를 선언하므로, 어느 쪽이 먼저 뜨든 결과가 같고
 * {@code PRECONDITION_FAILED} 가 구조적으로 나올 수 없다(spec.md §9 "큐 인자 불일치").
 *
 * <p>{@code app.ai.outbox.enabled=false}(기본값)이면 이 설정 자체가 통째로 비활성이다 — 즉
 * 전환 전에는 app-main 이 토폴로지를 선언하지도, 브로커에 연결하지도 않는다.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.ai.outbox", name = "enabled", havingValue = "true")
public class AiJobRabbitConfig {

	private static final Logger log = LoggerFactory.getLogger(AiJobRabbitConfig.class);

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

	// --- queue: question-answer dispatch ---

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

	// --- queue: accepted-answer knowledge ingest ---

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

	// --- template ---

	@Bean
	MessageConverter aiJobMessageConverter() {
		return new JacksonJsonMessageConverter();
	}

	/**
	 * confirm/return 콜백을 등록한 발행 템플릿.
	 *
	 * <p>{@code mandatory}·confirm 모드 등 {@code spring.rabbitmq.template.*} 설정은
	 * {@link RabbitTemplateConfigurer}가 그대로 적용한다 — 여기서 직접 세팅하면 프로퍼티와 어긋난다.
	 *
	 * <p>콜백은 <b>로깅 전용</b>이다. relay 의 정산 판단은 {@code CorrelationData#getFuture()} 로 하며,
	 * 그 future 는 {@link RabbitTemplate} 이 콜백과 무관하게 완료시킨다.
	 */
	@Bean
	RabbitTemplate rabbitTemplate(
		RabbitTemplateConfigurer configurer,
		ConnectionFactory connectionFactory,
		MessageConverter aiJobMessageConverter
	) {
		RabbitTemplate template = new RabbitTemplate();
		configurer.configure(template, connectionFactory);
		template.setMessageConverter(aiJobMessageConverter);
		template.setConfirmCallback((correlationData, ack, cause) -> {
			if (!ack) {
				log.warn(
					"event=ai_job_publish_nack correlationId={} cause={}",
					correlationData == null ? null : correlationData.getId(), cause
				);
			}
		});
		template.setReturnsCallback(returned -> log.warn(
			"event=ai_job_publish_returned exchange={} routingKey={} replyCode={} replyText={}",
			returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()
		));
		return template;
	}

	/** relay 파라미터. {@code ReportAiWorkerConfiguration}과 같은 방식으로 명시 바인딩한다. */
	@Bean
	AiJobOutboxProperties aiJobOutboxProperties(
		@Value("${app.ai.outbox.worker-id:${HOSTNAME:local}-${random.uuid}}") String workerId,
		@Value("${app.ai.outbox.lease:60s}") Duration lease,
		@Value("${app.ai.outbox.max-attempts:8}") int maxAttempts,
		@Value("${app.ai.outbox.batch-size:32}") int batchSize,
		@Value("${app.ai.outbox.confirm-timeout:5s}") Duration confirmTimeout,
		@Value("${app.ai.outbox.retention-days:7}") int retentionDays,
		@Value("${app.ai.outbox.backlog-warn-threshold:100}") long backlogWarnThreshold
	) {
		return new AiJobOutboxProperties(
			workerId, lease, maxAttempts, batchSize, confirmTimeout, retentionDays, backlogWarnThreshold
		);
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
