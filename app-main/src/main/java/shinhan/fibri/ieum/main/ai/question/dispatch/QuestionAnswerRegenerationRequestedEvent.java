package shinhan.fibri.ieum.main.ai.question.dispatch;

/**
 * 질문 수정으로 AI 티켓이 재무장(re-arm)됐을 때 afterCommit에 워커 dispatch wake를 트리거하는 이벤트.
 * QuestionCreatedEvent와 달리 알림을 발생시키지 않는다(수정은 신규 질문이 아님).
 */
public record QuestionAnswerRegenerationRequestedEvent(Long questionId) {
}
