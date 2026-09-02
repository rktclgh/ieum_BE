package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateConfigurer;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.main.ai.knowledge.dispatch.AcceptedAnswerKnowledgeJobDispatchListener;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;
import shinhan.fibri.ieum.main.ai.outbox.service.AiJobOutboxRelay;
import shinhan.fibri.ieum.main.ai.outbox.service.AiJobOutboxWriter;
import shinhan.fibri.ieum.main.ai.outbox.service.JpaAiJobOutboxWriter;
import shinhan.fibri.ieum.main.ai.outbox.service.NoOpAiJobOutboxWriter;
import shinhan.fibri.ieum.main.ai.question.dispatch.QuestionAnswerJobDispatchListener;

/**
 * Task 8 브리프의 핵심 계약 — {@code app.ai.dispatch.transport} 하나로 HTTP 디스패치 경로와 RabbitMQ
 * 발행 경로가 상호 배타적으로 갈리는지, 그리고 오타 값이 조용히 http 로 떨어지지 않고 기동을 막는지
 * 검증한다. 실제 브로커는 필요 없다 — {@code RabbitAutoConfiguration}을 등록하지 않고
 * {@link RabbitTemplateConfigurer}/{@link ConnectionFactory}를 모킹한다
 * ({@code AiResultConsumerFlagWiringTest}와 같은 패턴).
 */
class AiJobDispatchTransportSwitchTest {

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			// ApplicationContextRunner 는 맨몸 컨텍스트라 Boot 가 실제 앱에 넣어주는 String -> Duration
			// 변환기가 없다 — AiJobOutboxProperties 의 @Value Duration 바인딩에 필요하다.
			.withInitializer(context -> context.getBeanFactory()
				.setConversionService(ApplicationConversionService.getSharedInstance()))
			.withUserConfiguration(
				AiJobDispatchTransportConfig.class,
				QuestionAnswerDispatchConfig.class,
				AcceptedAnswerKnowledgeDispatchConfig.class,
				AiJobRabbitConfig.class,
				AiResultRabbitConfig.class,
				AiJobOutboxRelay.class,
				JpaAiJobOutboxWriter.class,
				NoOpAiJobOutboxWriter.class
			)
			.withPropertyValues(
				"app.ai.question-answer-dispatch.enabled=true",
				"app.ai.accepted-answer-dispatch.enabled=true",
				"app.ai.question-answer-dispatch.base-url=http://app-ai.internal:8081",
				"app.ai.question-answer-dispatch.allowed-hosts=app-ai.internal",
				"app.ai.accepted-answer-dispatch.base-url=http://app-ai.internal:8081",
				"app.ai.accepted-answer-dispatch.allowed-hosts=app-ai.internal"
			)
			.withBean(RabbitTemplateConfigurer.class, AiJobDispatchTransportSwitchTest::stubConfigurer)
			.withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
			.withBean(AiJobOutboxRepository.class, () -> mock(AiJobOutboxRepository.class))
			.withBean(ObjectMapper.class, ObjectMapper::new);
	}

	/**
	 * 진짜 {@code RabbitTemplateConfigurer.configure(...)}는 {@code template.setConnectionFactory(...)}를
	 * 호출해 준다 — 통째로 모킹하면 그 호출이 사라져 {@code RabbitTemplate.afterPropertiesSet()}이
	 * "ConnectionFactory is required"로 실패한다.
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

	@Test
	void httpTransportKeepsHttpListenersAndUsesTheNoOpWriter() {
		runner()
			.withPropertyValues("app.ai.dispatch.transport=http")
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionAnswerJobDispatchListener.class);
				assertThat(context).hasSingleBean(AcceptedAnswerKnowledgeJobDispatchListener.class);
				assertThat(context).doesNotHaveBean(AiJobOutboxRelay.class);
				assertThat(context.getBean(AiJobOutboxWriter.class)).isInstanceOf(NoOpAiJobOutboxWriter.class);
			});
	}

	@Test
	void rabbitmqTransportDropsHttpListenersAndUsesTheRealWriter() {
		runner()
			.withPropertyValues("app.ai.dispatch.transport=rabbitmq")
			.run(context -> {
				assertThat(context).doesNotHaveBean(QuestionAnswerJobDispatchListener.class);
				assertThat(context).doesNotHaveBean(AcceptedAnswerKnowledgeJobDispatchListener.class);
				assertThat(context).hasSingleBean(AiJobOutboxRelay.class);
				assertThat(context.getBean(AiJobOutboxWriter.class)).isInstanceOf(JpaAiJobOutboxWriter.class);
			});
	}

	@Test
	void missingTransportDefaultsToHttp() {
		runner()
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionAnswerJobDispatchListener.class);
				assertThat(context).doesNotHaveBean(AiJobOutboxRelay.class);
				assertThat(context.getBean(AiJobOutboxWriter.class)).isInstanceOf(NoOpAiJobOutboxWriter.class);
			});
	}

	@Test
	void unknownTransportValueFailsContextStartup() {
		runner()
			.withPropertyValues("app.ai.dispatch.transport=carrier-pigeon")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.rootCause()
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("app.ai.dispatch.transport")
					.hasMessageContaining("carrier-pigeon");
			});
	}
}
