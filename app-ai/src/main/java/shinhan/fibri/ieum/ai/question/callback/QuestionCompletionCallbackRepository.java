package shinhan.fibri.ieum.ai.question.callback;

import java.util.Optional;

public interface QuestionCompletionCallbackRepository {

	Optional<PendingQuestionCompletion> findPending(long questionId);

	boolean existsByQuestionId(long questionId);
}
