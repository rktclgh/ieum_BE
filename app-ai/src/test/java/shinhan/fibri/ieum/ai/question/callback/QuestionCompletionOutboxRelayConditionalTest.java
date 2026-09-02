package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link QuestionCompletionOutboxRelay}가 세 프로퍼티를 모두 확인하고서야 켜지는지 검증한다(리뷰 발견
 * 사항 I2). {@code app.ai.completion-relay.enabled=true} 하나만으로는 부족하다 —
 * {@code app.ai.features.question-answer-enabled}이 꺼져 있으면 이 빈의 생성자 의존성인
 * {@link QuestionCompletionCallbackClient}가 아예 존재하지 않고(예전에는 이 조합이 컨텍스트 기동
 * 실패로 이어졌다), {@code app.ai.question-answer.callback.transport=http}이면 relay 를 켜는 것 자체가
 * "HTTP 콜백 재폴러"라는 잘못된 조합이라 막아야 한다.
 */
class QuestionCompletionOutboxRelayConditionalTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(QuestionCompletionCallbackConfiguration.class, QuestionCompletionOutboxRelay.class)
		.withBean(QuestionCompletionCallbackRepository.class, () -> mock(QuestionCompletionCallbackRepository.class));

	@Test
	void relayEnabledButQuestionAnswerFeatureOffCreatesNoRelayBeanAndStillStarts() {
		contextRunner
			.withPropertyValues("app.ai.completion-relay.enabled=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(QuestionCompletionOutboxRelay.class);
			});
	}

	@Test
	void relayEnabledButCallbackTransportHttpCreatesNoRelayBean() {
		contextRunner
			.withPropertyValues(
				"app.ai.completion-relay.enabled=true",
				"app.ai.features.question-answer-enabled=true",
				"app.ai.question-answer.callback.base-origin=http://app-main.internal:8080",
				"app.ai.question-answer.callback.allowed-origins=http://app-main.internal:8080",
				"app.ai.question-answer.callback.internal-token=shared-secret"
				// transport defaults to http (matchIfMissing = true)
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(QuestionCompletionOutboxRelay.class);
			});
	}

	@Test
	void allThreePropertiesTrueCreatesTheRelayBean() {
		contextRunner
			.withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withPropertyValues(
				"app.ai.completion-relay.enabled=true",
				"app.ai.features.question-answer-enabled=true",
				"app.ai.question-answer.callback.base-origin=http://app-main.internal:8080",
				"app.ai.question-answer.callback.allowed-origins=http://app-main.internal:8080",
				"app.ai.question-answer.callback.internal-token=shared-secret",
				"app.ai.question-answer.callback.transport=rabbitmq"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(QuestionCompletionOutboxRelay.class);
			});
	}

	@Test
	void relayDisabledByDefaultCreatesNoRelayBean() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(QuestionCompletionOutboxRelay.class);
		});
	}
}
