package shinhan.fibri.ieum.ai.question.callback;

@FunctionalInterface
public interface QuestionCompletionCallbackClient {

	CallbackHttpResult deliver(long questionId, long answerId);
}
