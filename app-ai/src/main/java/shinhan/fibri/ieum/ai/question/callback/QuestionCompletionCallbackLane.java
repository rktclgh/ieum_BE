package shinhan.fibri.ieum.ai.question.callback;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;

public class QuestionCompletionCallbackLane implements QuestionCompletionCallbackWake {

	private static final Logger log = LoggerFactory.getLogger(QuestionCompletionCallbackLane.class);

	private final Executor executor;
	private final LongConsumer delivery;
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

	public QuestionCompletionCallbackLane(Executor executor, LongConsumer delivery) {
		this.executor = executor;
		this.delivery = delivery;
	}

	@Override
	public void wake(long questionId) {
		CallbackSubmission result = submit(questionId);
		if (result == CallbackSubmission.SATURATED) {
			log.warn("Question completion callback queue is saturated questionId={}", questionId);
		}
	}

	public CallbackSubmission submit(long questionId) {
		if (questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (!inFlight.add(questionId)) {
			return CallbackSubmission.ALREADY_ACTIVE;
		}
		try {
			executor.execute(() -> deliverAndRelease(questionId));
			return CallbackSubmission.ENQUEUED;
		}
		catch (RejectedExecutionException exception) {
			inFlight.remove(questionId);
			return CallbackSubmission.SATURATED;
		}
		catch (RuntimeException exception) {
			inFlight.remove(questionId);
			throw exception;
		}
	}

	private void deliverAndRelease(long questionId) {
		try {
			delivery.accept(questionId);
		}
		catch (RuntimeException exception) {
			log.error(
				"Unexpected question completion callback delivery failure questionId={} errorType={}",
				questionId,
				exception.getClass().getSimpleName()
			);
		}
		finally {
			inFlight.remove(questionId);
		}
	}
}
