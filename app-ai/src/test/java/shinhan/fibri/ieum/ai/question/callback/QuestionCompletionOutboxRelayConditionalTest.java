package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

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

	/**
	 * CodeRabbit PR #257 finding 1. relay 가 app-ai 의 공유 기본 스케줄러(풀 크기 1)를 그대로 쓰면
	 * {@code deliver}가 최대 5초까지 걸리는 배치 처리가 다른 모든 {@code @Scheduled} 작업(예:
	 * {@code KnowledgeRelationCandidateTaskRecovery})을 몇 분씩 밀어낼 수 있다. relay 가 켜질 때는
	 * 전용 단일 스레드 스케줄러 빈도 함께 떠야 한다.
	 */
	@Test
	void allThreePropertiesTrueAlsoCreatesTheDedicatedRelayScheduler() {
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
				assertThat(context).hasBean(QuestionCompletionOutboxRelay.RELAY_SCHEDULER_BEAN_NAME);
				assertThat(context.getBean(QuestionCompletionOutboxRelay.RELAY_SCHEDULER_BEAN_NAME))
					.isInstanceOf(TaskScheduler.class);
			});
	}

	/** relay 가 꺼져 있으면 전용 스케줄러도 만들 이유가 없다 — 관련 빈이 함께 없어야 한다. */
	@Test
	void relayDisabledByDefaultCreatesNoDedicatedScheduler() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(QuestionCompletionOutboxRelay.RELAY_SCHEDULER_BEAN_NAME);
		});
	}

	/**
	 * relay 메서드가 실제로 전용 스케줄러 빈 이름을 참조하는지 어노테이션 메타데이터로 직접 확인한다
	 * — app-ai 는 컨텍스트 전역에 {@code @EnableScheduling}이 없어(별도 모듈에서 켜진다) 이
	 * 컨텍스트-러너 테스트만으로는 스케줄링 자체가 실행되지 않기 때문에, "공유 기본 스케줄러가 아닌
	 * 전용 스케줄러에서 돈다"는 계약을 어노테이션 값으로 고정한다.
	 */
	@Test
	void relayMethodIsScheduledOnItsOwnDedicatedSchedulerNotTheSharedDefaultOne() throws NoSuchMethodException {
		Scheduled scheduled = QuestionCompletionOutboxRelay.class
			.getMethod("relayPendingCompletions")
			.getAnnotation(Scheduled.class);
		assertThat(scheduled).isNotNull();
		assertThat(scheduled.scheduler()).isEqualTo(QuestionCompletionOutboxRelay.RELAY_SCHEDULER_BEAN_NAME);
	}
}
