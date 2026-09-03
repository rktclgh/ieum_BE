package shinhan.fibri.ieum.main.ai.outbox.service;

/**
 * 도메인 트랜잭션 안에서 AI 작업을 큐잉한다. spec.md §7.3/§8.1, Task 8.
 *
 * <p>구현은 {@code app.ai.dispatch.transport}로 갈린다(application.properties의 해당 프로퍼티 주석이
 * 단일 진실 원천):
 * <ul>
 *   <li>{@code rabbitmq} — {@link JpaAiJobOutboxWriter}. {@code ai_job_outbox} row를 실제로 쓰고,
 *       {@link AiJobOutboxRelay}가 그 row를 발행한다.</li>
 *   <li>{@code http}(기본값) — {@link NoOpAiJobOutboxWriter}. 아무 것도 쓰지 않는다 — HTTP 디스패치
 *       경로가 살아 있는 동안 outbox에 고아 row가 쌓이는 사고를 막는다.</li>
 * </ul>
 *
 * <p>{@link shinhan.fibri.ieum.main.question.service.QuestionService}/
 * {@link shinhan.fibri.ieum.main.answer.service.AnswerService}는 이 인터페이스에만 의존한다 —
 * 전송 방식이 바뀌어도 호출부는 고치지 않는다(Task 8 브리프).
 */
public interface AiJobOutboxWriter {

	void enqueueQuestionAnswerDispatch(Long questionId, Reason reason);

	void enqueueAcceptedAnswerKnowledge(Long answerId);
}
