package shinhan.fibri.ieum.main.ai.outbox.service;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@code app.ai.dispatch.transport=http}(기본값)일 때의 outbox writer. 아무 row도 쓰지 않는다.
 *
 * <p><b>존재 이유</b>: HTTP 디스패치 경로가 살아 있는 동안 {@code ai_job_outbox}에 발행 워커
 * ({@link AiJobOutboxRelay})가 꺼진 채로 row 만 쌓이면 고아 row가 무한히 늘어난다(불변식:
 * "transport=http에서 outbox row가 생기지 않음").
 * {@link shinhan.fibri.ieum.main.question.service.QuestionService}/
 * {@link shinhan.fibri.ieum.main.answer.service.AnswerService}는 이 인터페이스에만 의존하므로
 * 이 구현으로 바뀌어도 호출부는 그대로다.
 *
 * <p>인자 검증은 {@link JpaAiJobOutboxWriter}와 동일하게 유지한다 — transport 전환만으로 호출부의
 * 오류 계약(양수 ID 요구 등)이 조용히 느슨해지는 것을 막는다.
 */
@Service
@ConditionalOnProperty(name = "app.ai.dispatch.transport", havingValue = "http", matchIfMissing = true)
public class NoOpAiJobOutboxWriter implements AiJobOutboxWriter {

	private static final Logger log = LoggerFactory.getLogger(NoOpAiJobOutboxWriter.class);

	@Override
	public void enqueueQuestionAnswerDispatch(Long questionId, Reason reason) {
		if (questionId == null || questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive: " + questionId);
		}
		Objects.requireNonNull(reason, "reason must not be null");
		log.debug(
			"event=ai_job_outbox_noop_skip jobType=question_answer_dispatch questionId={} reason={}",
			questionId, reason.value()
		);
	}

	@Override
	public void enqueueAcceptedAnswerKnowledge(Long answerId) {
		if (answerId == null || answerId <= 0) {
			throw new IllegalArgumentException("answerId must be positive: " + answerId);
		}
		log.debug(
			"event=ai_job_outbox_noop_skip jobType=accepted_answer_knowledge_ingest answerId={}", answerId
		);
	}
}
