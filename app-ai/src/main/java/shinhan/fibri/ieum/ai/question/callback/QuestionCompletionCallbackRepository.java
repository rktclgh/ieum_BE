package shinhan.fibri.ieum.ai.question.callback;

import java.util.List;
import java.util.Optional;

public interface QuestionCompletionCallbackRepository {

	Optional<PendingQuestionCompletion> findPending(long questionId);

	List<Long> findPendingQuestionIds(int limit);

	boolean existsByQuestionId(long questionId);
}
