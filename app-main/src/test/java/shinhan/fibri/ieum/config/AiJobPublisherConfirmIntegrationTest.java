package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.testsupport.AiJobRabbitContainer;

/**
 * publisher confirms 와 mandatory return 이 실제 브로커에서 동작하는지 확인한다. spec.md §7.3/§11.4.
 *
 * <p>이게 GREEN 이어야 relay 의 정산 분기(ack / nack / returned / timeout)가 의미를 갖는다.
 */
@EnabledIf(value = "shinhan.fibri.ieum.testsupport.DockerAvailability#isAvailable", disabledReason = "Docker unavailable")
class AiJobPublisherConfirmIntegrationTest {

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withInitializer(bootConversionService())
			.withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
			// AiResultRabbitConfig 가 aiRetryExchange/aiDlxExchange(디스패치 큐의 retry/DLQ 바인딩이
			// 참조하는 공유 exchange)를 선언한다 — 리뷰 라운드 1 finding으로 분리된 클래스, 없으면
			// aiQuestionAnswerDispatchRetryBinding 등의 빈 생성이 실패한다.
			.withUserConfiguration(AiJobRabbitConfig.class, AiResultRabbitConfig.class)
			.withPropertyValues(
				"app.ai.outbox.enabled=true",
				"spring.rabbitmq.host=" + AiJobRabbitContainer.host(),
				"spring.rabbitmq.port=" + AiJobRabbitContainer.amqpPort(),
				"spring.rabbitmq.username=" + AiJobRabbitContainer.username(),
				"spring.rabbitmq.password=" + AiJobRabbitContainer.password(),
				"spring.rabbitmq.publisher-confirm-type=correlated",
				"spring.rabbitmq.publisher-returns=true",
				"spring.rabbitmq.template.mandatory=true"
			);
	}

	private Message persistentJson(String jobId) {
		MessageProperties properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setContentEncoding(StandardCharsets.UTF_8.name());
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(jobId);
		properties.setCorrelationId(jobId);
		return new Message("{\"jobId\":\"%s\"}".formatted(jobId).getBytes(StandardCharsets.UTF_8), properties);
	}

	@Test
	void boundRoutingKeyIsAckedAndPersistedOnTheQueue() {
		runner().run(context -> {
			RabbitAdmin admin = context.getBean(RabbitAdmin.class);
			admin.initialize();
			admin.purgeQueue(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH, false);
			RabbitTemplate template = context.getBean(RabbitTemplate.class);

			String jobId = UUID.randomUUID().toString();
			CorrelationData correlation = new CorrelationData(jobId);
			template.send(
				AiJobTopology.EXCHANGE_JOBS,
				AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH,
				persistentJson(jobId),
				correlation
			);

			CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
			assertThat(confirm.ack()).isTrue();
			assertThat(correlation.getReturned()).isNull();

			Message received = template.receive(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH, 5_000);
			assertThat(received).isNotNull();
			assertThat(received.getMessageProperties().getReceivedDeliveryMode())
				.isEqualTo(MessageDeliveryMode.PERSISTENT);
			assertThat(received.getMessageProperties().getMessageId()).isEqualTo(jobId);
			assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).contains(jobId);
		});
	}

	@Test
	void unboundRoutingKeyTriggersAMandatoryReturn() {
		runner().run(context -> {
			RabbitAdmin admin = context.getBean(RabbitAdmin.class);
			admin.initialize();
			RabbitTemplate template = context.getBean(RabbitTemplate.class);

			String jobId = UUID.randomUUID().toString();
			CorrelationData correlation = new CorrelationData(jobId);
			template.send(AiJobTopology.EXCHANGE_JOBS, "ai.nobody.listens", persistentJson(jobId), correlation);

			CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
			assertThat(confirm.ack()).isTrue();
			assertThat(correlation.getReturned()).isNotNull();
			assertThat(correlation.getReturned().getReplyText()).contains("NO_ROUTE");
		});
	}

	/**
	 * {@code ApplicationContextRunner}는 맨몸 컨텍스트라 Boot 가 실제 앱에 넣어주는
	 * {@code String -> Duration} 변환기가 없다. 실제 기동과 같은 조건으로 맞춘다.
	 */
	private static org.springframework.context.ApplicationContextInitializer<
		org.springframework.context.ConfigurableApplicationContext> bootConversionService() {
		return context -> context.getBeanFactory()
			.setConversionService(ApplicationConversionService.getSharedInstance());
	}

}
