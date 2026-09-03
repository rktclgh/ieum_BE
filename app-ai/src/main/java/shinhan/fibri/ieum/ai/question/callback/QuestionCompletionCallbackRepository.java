package shinhan.fibri.ieum.ai.question.callback;

import java.util.List;
import java.util.Optional;

public interface QuestionCompletionCallbackRepository {

	Optional<PendingQuestionCompletion> findPending(long questionId);

	boolean existsByQuestionId(long questionId);

	/**
	 * {@link #findPending(long)}과 같은 술어(spec.md §5.4)에서 {@code question_id} 조건만 뺀
	 * 배치 조회다 — {@link QuestionCompletionOutboxRelay}가 안전망 재발행에 쓴다.
	 * {@code question_id} 오름차순으로 최대 {@code limit}건을 돌려준다.
	 */
	List<PendingQuestionCompletion> findPendingBatch(int limit);
}
