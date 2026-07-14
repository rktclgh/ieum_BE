package shinhan.fibri.ieum.ai.question.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;

public class QuestionAnswerTaskLane {

	private final boolean enabled;
	private final Executor executor;
	private final LongConsumer processor;
	private final Set<Long> activeQuestionIds = ConcurrentHashMap.newKeySet();

	public QuestionAnswerTaskLane(boolean enabled, Executor executor, LongConsumer processor) {
		this.enabled = enabled;
		this.executor = executor;
		this.processor = processor;
	}

	public QuestionAnswerTaskSubmission submit(long questionId) {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (!enabled) {
			return QuestionAnswerTaskSubmission.DISABLED;
		}
		if (!activeQuestionIds.add(questionId)) {
			return QuestionAnswerTaskSubmission.ALREADY_ACTIVE;
		}
		try {
			executor.execute(() -> {
				try {
					processor.accept(questionId);
				}
				finally {
					activeQuestionIds.remove(questionId);
				}
			});
			return QuestionAnswerTaskSubmission.ENQUEUED;
		}
		catch (RejectedExecutionException exception) {
			activeQuestionIds.remove(questionId);
			return QuestionAnswerTaskSubmission.SATURATED;
		}
		catch (RuntimeException exception) {
			activeQuestionIds.remove(questionId);
			throw exception;
		}
	}

	public boolean isEnabled() {
		return enabled;
	}
}
