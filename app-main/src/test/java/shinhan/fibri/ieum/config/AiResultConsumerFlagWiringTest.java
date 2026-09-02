package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;
import shinhan.fibri.ieum.main.ai.outbox.service.AiJobOutboxRelay;
import shinhan.fibri.ieum.main.ai.result.QuestionAnswerCompletedMessageListener;
import shinhan.fibri.ieum.main.ai.result.dlq.AiResultDeadLetterPublisher;
import shinhan.fibri.ieum.main.notification.internal.AiQuestionAnswerCompletionService;

/**
 * 리뷰 라운드 1 finding — 결과 컨슈머({@link QuestionAnswerCompletedMessageListener})가
 * {@code app.ai.outbox.enabled}(디스패치 relay 플래그, spec.md §11.5 Stage 3)에 잘못 묶여 있어서
 * Stage 2("app-main 결과 컨슈머가 받는다")의 완료 메시지가 소비자 없이 쌓이던 문제를 고쳤는지
 * 검증한다.
 *
 * <p>app-ai 의 {@code QuestionCompletionCallbackConfigurationTest}와 같은 스타일 — 실제 브로커 없이
 * ({@code RabbitAutoConfiguration}을 등록하지 않는다) 순수 빈 그래프만으로 조건부 활성화를 검증한다.
 * {@link AiJobRabbitConfig#rabbitTemplate}이 요구하는 {@link RabbitTemplateConfigurer}/
 * {@link ConnectionFactory}는 모킹해서, 실제 브로커 연결 없이도 빈 생성 자체는 성공하게 한다
 * (둘 다 로컬 객체 조립만 하고 네트워크를 건드리지 않는다 — {@code RabbitAdmin}이 없으므로 자동
 * 선언도 일어나지 않는다).
 *
 * <p>{@code AiJobOutboxProperties}는 일부러 여기서 공급하지 않는다 — {@link AiJobRabbitConfig} 자체가
 * {@code app.ai.outbox.enabled=true}일 때 그 빈을 직접 만든다. 여기서 별도로 공급하면 outbox 가
 * 켜진 시나리오에서 {@code BeanDefinitionOverrideException}(같은 이름의 빈 중복 정의)이 난다.
 */
class AiResultConsumerFlagWiringTest {

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			// ApplicationContextRunner 는 맨몸 컨텍스트라 Boot 가 실제 앱에 넣어주는 String -> Duration
			// 변환기가 없다 — AiJobOutboxProperties 의 @Value Duration 바인딩에 필요하다
			// (AiJobPublisherConfirmIntegrationTest 와 같은 이유).
			.withInitializer(context -> context.getBeanFactory()
				.setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
			.withUserConfiguration(
				AiJobRabbitConfig.class,
				AiResultRabbitConfig.class,
				AiJobOutboxRelay.class,
				QuestionAnswerCompletedMessageListener.class
			)
			.withBean(RabbitTemplateConfigurer.class, AiResultConsumerFlagWiringTest::stubConfigurer)
			.withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
			.withBean(AiJobOutboxRepository.class, () -> mock(AiJobOutboxRepository.class))
			.withBean(AiQuestionAnswerCompletionService.class, () -> mock(AiQuestionAnswerCompletionService.class))
			.withBean(AiResultDeadLetterPublisher.class, () -> mock(AiResultDeadLetterPublisher.class))
			.withBean(ObjectMapper.class, ObjectMapper::new);
	}

	/**
	 * 진짜 {@code RabbitTemplateConfigurer.configure(...)}는 {@code template.setConnectionFactory(...)}를
	 * 호출해 준다 — 통째로 모킹하면 그 호출이 사라져 {@code RabbitTemplate.afterPropertiesSet()}이
	 * "ConnectionFactory is required"로 실패한다. 그 한 가지 부작용만 재현한다.
	 */
	private static RabbitTemplateConfigurer stubConfigurer() {
		RabbitTemplateConfigurer configurer = mock(RabbitTemplateConfigurer.class);
		doAnswer(invocation -> {
			RabbitTemplate template = invocation.getArgument(0);
			ConnectionFactory connectionFactory = invocation.getArgument(1);
			template.setConnectionFactory(connectionFactory);
			return null;
		}).when(configurer).configure(any(RabbitTemplate.class), any(ConnectionFactory.class));
		return configurer;
	}

	/** {@code getBeansOfType(Queue.class)}의 맵 키는 Spring 빈 이름이지 AMQP 큐 이름이 아니다 — 실제 선언된 AMQP 이름만 비교한다. */
	private static java.util.List<String> declaredQueueNames(
		org.springframework.boot.test.context.assertj.AssertableApplicationContext context
	) {
		return context.getBeansOfType(Queue.class).values().stream().map(Queue::getName).toList();
	}

	@Test
	void resultConsumerOnAndOutboxOffStartsTheListenerAndDeclaresTheResultsQueue() {
		runner()
			.withPropertyValues("app.ai.result.consumer.enabled=true", "app.ai.outbox.enabled=false")
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionAnswerCompletedMessageListener.class);
				assertThat(context).doesNotHaveBean(AiJobOutboxRelay.class);
				assertThat(declaredQueueNames(context))
					.as("결과 큐 3종(work/retry/dlq)만 선언된다 — 디스패치 relay 는 꺼져 있다")
					.containsExactlyInAnyOrder(
						AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED,
						AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY,
						AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ
					);
			});
	}

	@Test
	void resultConsumerDefaultsOnEvenWithoutSettingThePropertyAtAll() {
		// Stage 2(spec.md §11.5)는 app-main 쪽 프로퍼티를 하나도 뒤집지 않고도 결과 컨슈머가
		// 이미 떠 있기를 요구한다 — 즉 matchIfMissing=true 가 이 요구사항의 핵심이다.
		runner()
			.withPropertyValues("app.ai.outbox.enabled=false")
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionAnswerCompletedMessageListener.class);
				assertThat(declaredQueueNames(context)).contains(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED);
			});
	}

	@Test
	void bothFlagsOffStartsNothing() {
		runner()
			.withPropertyValues("app.ai.result.consumer.enabled=false", "app.ai.outbox.enabled=false")
			.run(context -> {
				assertThat(context).doesNotHaveBean(QuestionAnswerCompletedMessageListener.class);
				assertThat(context).doesNotHaveBean(AiJobOutboxRelay.class);
				assertThat(context.getBeansOfType(Queue.class)).isEmpty();
			});
	}

	@Test
	void outboxOnAndResultConsumerOffStartsTheRelayButNotTheListener() {
		runner()
			.withPropertyValues("app.ai.result.consumer.enabled=false", "app.ai.outbox.enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(AiJobOutboxRelay.class);
				assertThat(context).doesNotHaveBean(QuestionAnswerCompletedMessageListener.class);
				// aiRetryExchange/aiDlxExchange 는 공유 exchange 라 디스패치 큐(work/retry/dlq x2)와
				// 완료 큐(work/retry/dlq) 까지 전부 여전히 선언돼야 한다 — 결과 컨슈머를 꺼도
				// AiResultRabbitConfig 자체는 outbox 플래그로도 활성화된다(AnyNestedCondition).
				assertThat(declaredQueueNames(context)).containsExactlyInAnyOrder(
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
				assertThat(context).hasSingleBean(RabbitTemplate.class);
			});
	}
}
