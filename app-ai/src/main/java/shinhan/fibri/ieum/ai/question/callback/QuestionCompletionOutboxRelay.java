package shinhan.fibri.ieum.ai.question.callback;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
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
 */
@Service
@ConditionalOnProperty(name = "app.ai.completion-relay.enabled", havingValue = "true")
public class QuestionCompletionOutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(QuestionCompletionOutboxRelay.class);

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

	@Scheduled(fixedDelayString = "${app.ai.question-answer.callback.recovery-interval:60s}")
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
}
