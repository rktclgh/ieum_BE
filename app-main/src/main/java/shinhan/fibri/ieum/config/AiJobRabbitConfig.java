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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.outbox.service.AiJobOutboxProperties;

/**
 * app-main 이 발행하는 디스패치 계열 큐 토폴로지(질문 답변 dispatch, 채택 답변 지식화) + 발행용
 * {@link RabbitTemplate}. spec.md §6.2, §7.1, §11.4.
 *
 * <p>app-ai 도 같은 {@link AiJobTopology} 상수로 같은 인자를 선언하므로, 어느 쪽이 먼저 뜨든 결과가
 * 같고 {@code PRECONDITION_FAILED} 가 구조적으로 나올 수 없다(spec.md §9 "큐 인자 불일치").
 *
 * <p>운영은 {@code app.ai.dispatch.transport=rabbitmq}로 이 설정이 활성이다. 코드 기본값
 * {@code http}는 브로커 없는 로컬 개발·운영 롤백 모드로, 그때는 이 설정 자체가 통째로 비활성이다 — 즉
 * app-main 이 디스패치를 발행하지도, 이 클래스를 통해 브로커에 연결하지도 않는다. 예전에는 이 플래그가
 * {@code app.ai.outbox.enabled}였다 — {@code app.ai.dispatch.transport} 하나로 HTTP 리스너
 * 비활성화까지 함께 묶기 위해 대체했다(단일 스위치, application.properties 참고).
 *
 * <p><b>완료 결과(콜백) 큐 토폴로지는 여기 없다</b> — {@link AiResultRabbitConfig}로 분리했다.
 * app-ai 가 완료 통보를 브로커로 발행하므로 app-main 결과 컨슈머는 이 클래스의 플래그
 * ({@code transport=rabbitmq})와 독립적으로 항상 살아 있어야 한다. 결과 토폴로지를 이 플래그에 묶어
 * 두면 http 롤백 중 완료 메시지가 소비자 없이 쌓인다. {@code aiRetryExchange}/{@code aiDlxExchange}는
 * 이 클래스의 디스패치 큐도 참조하는 공유 exchange라 {@link AiResultRabbitConfig}에서 선언하고
 * 여기서는 빈 참조로만 받는다 — {@link AiResultRabbitConfig}는 디스패치 transport 가 rabbitmq 여도
 * 활성화되므로({@code RabbitTopologyRequiredCondition}) 이 클래스가 살아 있는 한 그 두 exchange
 * 빈도 항상 함께 존재한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.ai.dispatch.transport", havingValue = "rabbitmq")
public class AiJobRabbitConfig {

	private static final Logger log = LoggerFactory.getLogger(AiJobRabbitConfig.class);

	private static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
	private static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";
	private static final String ARG_MESSAGE_TTL = "x-message-ttl";

	// --- exchange (aiRetryExchange/aiDlxExchange 는 AiResultRabbitConfig 가 선언한다) ---

	@Bean
	DirectExchange aiJobsExchange() {
		return durableDirect(AiJobTopology.EXCHANGE_JOBS);
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

	/**
	 * {@code aiRetryExchange}는 {@link AiResultRabbitConfig}가 선언한다 — 빈 이름으로 주입받는다.
	 * {@code -parameters} 컴파일 플래그(파라미터 이름 기반 by-name 주입)에만 기대지 않도록
	 * {@code @Qualifier}를 명시한다(CodeRabbit PR #257 finding 3) — 빌드 설정이 바뀌어도 안전하다.
	 */
	@Bean
	Binding aiQuestionAnswerDispatchRetryBinding(@Qualifier("aiRetryExchange") DirectExchange aiRetryExchange) {
		return bind(aiQuestionAnswerDispatchRetryQueue(), aiRetryExchange,
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY);
	}

	/** {@code aiRetryExchange}는 {@link AiResultRabbitConfig}가 선언한다 — 빈 이름으로 주입받는다. */
	@Bean
	Binding aiAcceptedAnswerIngestRetryBinding(@Qualifier("aiRetryExchange") DirectExchange aiRetryExchange) {
		return bind(aiAcceptedAnswerIngestRetryQueue(), aiRetryExchange,
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY);
	}

	/** {@code aiDlxExchange}는 {@link AiResultRabbitConfig}가 선언한다 — 빈 이름으로 주입받는다. */
	@Bean
	Binding aiQuestionAnswerDispatchDlqBinding(@Qualifier("aiDlxExchange") DirectExchange aiDlxExchange) {
		return bind(aiQuestionAnswerDispatchDlq(), aiDlxExchange,
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ);
	}

	/** {@code aiDlxExchange}는 {@link AiResultRabbitConfig}가 선언한다 — 빈 이름으로 주입받는다. */
	@Bean
	Binding aiAcceptedAnswerIngestDlqBinding(@Qualifier("aiDlxExchange") DirectExchange aiDlxExchange) {
		return bind(aiAcceptedAnswerIngestDlq(), aiDlxExchange,
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_DLQ);
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
		@Value("${app.ai.outbox.lease:300s}") Duration lease,
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
