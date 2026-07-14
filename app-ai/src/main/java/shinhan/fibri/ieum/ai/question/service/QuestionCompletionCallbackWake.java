package shinhan.fibri.ieum.ai.question.service;

@FunctionalInterface
public interface QuestionCompletionCallbackWake {

	void wake(long questionId);
}
