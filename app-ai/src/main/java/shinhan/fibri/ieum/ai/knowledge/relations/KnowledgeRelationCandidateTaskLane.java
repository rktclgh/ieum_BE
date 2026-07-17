package shinhan.fibri.ieum.ai.knowledge.relations;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class KnowledgeRelationCandidateTaskLane {

	private final boolean enabled;
	private final Executor executor;
	private final KnowledgeRelationCandidateExtractionService service;

	public KnowledgeRelationCandidateTaskLane(
		boolean enabled,
		Executor executor,
		KnowledgeRelationCandidateExtractionService service
	) {
		this.enabled = enabled;
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
		this.service = service;
	}

	public boolean submit() {
		if (!enabled || service == null) {
			return false;
		}
		try {
			executor.execute(service::processNext);
			return true;
		}
		catch (RejectedExecutionException exception) {
			return false;
		}
	}

	public boolean isEnabled() {
		return enabled;
	}
}
