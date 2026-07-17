package shinhan.fibri.ieum.main.ai.question.repository;

public interface QuestionAnswerTicketWriter {

	void create(Long questionId);

	void requestCancellation(Long questionId);

	/**
	 * 진행 중인(=completed가 아닌) 티켓을 pending으로 되돌리고 모든 생성물 컬럼을 초기화한다.
	 * lease_token을 무효화하여 늦은 워커의 finalize CAS를 실패시킨다.
	 *
	 * @return 재무장이 실제로 이뤄졌으면 true(status가 completed가 아니었음). completed면 no-op이고 false.
	 */
	boolean requestRegeneration(Long questionId);
}
