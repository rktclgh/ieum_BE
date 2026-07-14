package shinhan.fibri.ieum.ai.knowledge.accepted.service;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;

public class AcceptedAnswerKnowledgeTaskLane {

	private final boolean enabled;
	private final Executor executor;
	private final LongConsumer processor;
	private final Set<Long> activeAnswerIds = ConcurrentHashMap.newKeySet();

	public AcceptedAnswerKnowledgeTaskLane(
		boolean enabled,
		Executor executor,
		LongConsumer processor
	) {
		this.enabled = enabled;
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
		this.processor = Objects.requireNonNull(processor, "processor must not be null");
	}

	public AcceptedAnswerKnowledgeTaskSubmission submit(long answerId) {
		if (answerId < 1) {
			throw new IllegalArgumentException("answerId must be positive");
		}
		if (!enabled) {
			return AcceptedAnswerKnowledgeTaskSubmission.DISABLED;
		}
		if (!activeAnswerIds.add(answerId)) {
			return AcceptedAnswerKnowledgeTaskSubmission.ALREADY_ACTIVE;
		}
		try {
			executor.execute(() -> process(answerId));
			return AcceptedAnswerKnowledgeTaskSubmission.ENQUEUED;
		}
		catch (RejectedExecutionException exception) {
			activeAnswerIds.remove(answerId);
			return AcceptedAnswerKnowledgeTaskSubmission.SATURATED;
		}
		catch (RuntimeException exception) {
			activeAnswerIds.remove(answerId);
			throw exception;
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	private void process(long answerId) {
		try {
			processor.accept(answerId);
		}
		finally {
			activeAnswerIds.remove(answerId);
		}
	}
}
