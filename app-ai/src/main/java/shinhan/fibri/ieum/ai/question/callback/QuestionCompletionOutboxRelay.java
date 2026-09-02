package shinhan.fibri.ieum.ai.question.callback;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 완료 통보 재발행 안전망. spec.md §8.5, 브리프 "구현 단계" 4번.
 *
 * <p>워커 완료 직후의 즉시 발행은 {@link QuestionCompletionCallbackLane}이 담당한다(변경하지 않는다).
 * 이 relay 는 그 즉시 발행이 실패했을 때(브로커 다운, confirm timeout, app-ai 재시작 등)의 안전망이다.
 * {@link QuestionCompletionCallbackRepository#findPendingBatch(int)}가 곧 outbox 조회다 —
 * {@code ai_question_tasks} 자체가 outbox 이기 때문에(spec.md §5.4) 별도 클레임/lease 도 없다.
 *
 * <p>{@code answer_notification_processed_at}이 app-main 소비 TX 에서 채워질 때까지 매 주기마다
 * 같은 row 를 계속 재발행한다 — 중복 메시지가 생길 수 있지만, app-main 의 알림 유니크 제약(§8.4)이
 * 흡수한다. 한 row 의 발행 실패(예외 또는 {@link CallbackHttpResult#FAILED})가 나머지 row 처리를
 * 막지 않는다 — {@code AiJobOutboxRelay.publishBatch}의 per-job 격리와 같은 이유다.
 *
 * <p><b>세 조건이 모두 참이어야 활성화된다</b>(리뷰 발견 사항 I2). {@code app.ai.completion-relay.enabled}
 * 하나만 보면, {@code app.ai.features.question-answer-enabled}(기본값 {@code false})가 꺼져 있어
 * {@link QuestionCompletionCallbackConfiguration} 자체가 비활성일 때 이 빈의 생성자 의존성인
 * {@link QuestionCompletionCallbackClient} 빈이 아예 없어 컨텍스트 기동이 실패한다. 또한
 * {@code app.ai.question-answer.callback.transport=http}일 때 relay 를 켜면 HTTP 콜백을 재발행하는
 * "재폴러"가 돼 버려 이 클래스의 존재 이유(브로커 왕복 안전망)와 맞지 않는다. 그래서
 * {@link RelayRequiredCondition}이 세 프로퍼티를 전부 확인한다.
 */
@Service
@Conditional(QuestionCompletionOutboxRelay.RelayRequiredCondition.class)
public class QuestionCompletionOutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(QuestionCompletionOutboxRelay.class);

	/**
	 * relay 전용 단일 스레드 스케줄러 빈 이름(CodeRabbit PR #257 finding 1). {@code deliver}가 최대
	 * 5초까지 걸리는 콜백 호출을 배치당 최대 {@code batchSize}번 직렬로 반복하므로, app-ai 의 공유
	 * 기본 {@code TaskScheduler}(Spring Boot 기본 풀 크기 1)를 그대로 쓰면 이 relay 배치 하나가
	 * {@link shinhan.fibri.ieum.ai.knowledge.relations.KnowledgeRelationCandidateTaskRecovery} 등
	 * 같은 풀을 쓰는 다른 모든 {@code @Scheduled} 작업을 몇 분씩 밀어낼 수 있다. relay 를 자기 전용
	 * 스레드로 분리해 서로 영향을 주지 않게 한다.
	 */
	static final String RELAY_SCHEDULER_BEAN_NAME = "questionCompletionRelayScheduler";

	private final QuestionCompletionCallbackRepository repository;
	private final QuestionCompletionCallbackClient client;
	private final int batchSize;

	public QuestionCompletionOutboxRelay(
		QuestionCompletionCallbackRepository repository,
		QuestionCompletionCallbackClient client,
		@Value("${app.ai.question-answer.callback.recovery-batch-size:32}") int batchSize
	) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.client = Objects.requireNonNull(client, "client must not be null");
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be positive");
		}
		this.batchSize = batchSize;
	}

	/**
	 * {@code scheduler}에 전용 빈 이름을 명시해 app-ai 공유 기본 스케줄러를 쓰지 않는다(위
	 * {@link #RELAY_SCHEDULER_BEAN_NAME} 참고, CodeRabbit PR #257 finding 1).
	 */
	@Bean(RELAY_SCHEDULER_BEAN_NAME)
	TaskScheduler questionCompletionRelayScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("question-completion-relay-");
		return scheduler;
	}

	@Scheduled(
		scheduler = RELAY_SCHEDULER_BEAN_NAME,
		fixedDelayString = "${app.ai.question-answer.callback.recovery-interval:60s}"
	)
	public void relayPendingCompletions() {
		List<PendingQuestionCompletion> batch;
		try {
			batch = repository.findPendingBatch(batchSize);
		}
		catch (RuntimeException failure) {
			log.error(
				"event=question_completion_relay_batch_read_failure failureType={}",
				failure.getClass().getSimpleName()
			);
			return;
		}
		if (batch.isEmpty()) {
			return;
		}
		log.debug("event=question_completion_relay_batch_claimed batchSize={}", batch.size());
		for (PendingQuestionCompletion pending : batch) {
			publishOne(pending);
		}
	}

	private void publishOne(PendingQuestionCompletion pending) {
		try {
			CallbackHttpResult result = client.deliver(pending.questionId(), pending.answerId());
			if (result != CallbackHttpResult.DELIVERED) {
				log.warn(
					"event=question_completion_relay_publish_not_delivered questionId={} answerId={} result={}",
					pending.questionId(), pending.answerId(), result
				);
			}
		}
		catch (RuntimeException failure) {
			// 이 row 의 예상 못한 실패가 나머지 배치를 건드리지 못하게 격리한다(브리프 "먼저 쓸
			// 테스트" 3번). 다음 주기에 같은 row 가 다시 조회돼 재시도된다 — outbox 는
			// answer_notification_processed_at 이 채워질 때까지 절대 비워지지 않기 때문이다.
			log.error(
				"event=question_completion_relay_row_failure questionId={} answerId={} failureType={}",
				pending.questionId(), pending.answerId(), failure.getClass().getSimpleName()
			);
		}
	}

	/**
	 * {@code REGISTER_BEAN} 이어야 한다 — 이 조건은 {@code @Configuration}이 아니라 이 클래스(평범한
	 * {@code @Service})에 직접 붙어 있으므로, 컴포넌트 스캔이 빈 정의를 등록하는 시점에 평가된다.
	 * app-main의 {@code AiResultRabbitConfig.RabbitTopologyRequiredCondition}과 달리 여기서
	 * {@code PARSE_CONFIGURATION}을 쓰면 안 된다 — 그건 {@code @Configuration} 클래스 자체에 붙은
	 * 조건에만 해당하는 이야기다.
	 */
	static class RelayRequiredCondition extends AllNestedConditions {

		RelayRequiredCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnProperty(name = "app.ai.completion-relay.enabled", havingValue = "true")
		static class RelayEnabled {
		}

		@ConditionalOnProperty(name = "app.ai.features.question-answer-enabled", havingValue = "true")
		static class QuestionAnswerFeatureEnabled {
		}

		@ConditionalOnProperty(name = "app.ai.question-answer.callback.transport", havingValue = "rabbitmq")
		static class CallbackTransportIsRabbitmq {
		}
	}
}
