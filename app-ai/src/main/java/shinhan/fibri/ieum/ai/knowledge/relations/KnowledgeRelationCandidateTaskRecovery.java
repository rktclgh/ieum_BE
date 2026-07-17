package shinhan.fibri.ieum.ai.knowledge.relations;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class KnowledgeRelationCandidateTaskRecovery {

	private static final int MAX_BATCH_SIZE = 32;

	private final KnowledgeRelationCandidateTaskLane lane;
	private final int maxTasksPerRun;

	public KnowledgeRelationCandidateTaskRecovery(
		KnowledgeRelationCandidateTaskLane lane,
		int maxTasksPerRun
	) {
		this.lane = Objects.requireNonNull(lane, "lane must not be null");
		if (maxTasksPerRun < 1 || maxTasksPerRun > MAX_BATCH_SIZE) {
			throw new IllegalArgumentException("maxTasksPerRun must be between 1 and " + MAX_BATCH_SIZE);
		}
		this.maxTasksPerRun = maxTasksPerRun;
	}

	@Scheduled(fixedDelayString = "${app.ai.knowledge-relation.recovery-interval:30s}")
	public void drain() {
		lane.drain(maxTasksPerRun);
	}
}
