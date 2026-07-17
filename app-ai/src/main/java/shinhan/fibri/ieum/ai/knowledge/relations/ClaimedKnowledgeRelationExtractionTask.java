package shinhan.fibri.ieum.ai.knowledge.relations;

import java.time.OffsetDateTime;
import java.util.UUID;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeDocument;

record ClaimedKnowledgeRelationExtractionTask(
	long taskId,
	long sourceId,
	long chunkId,
	UUID leaseToken,
	OffsetDateTime leaseUntil,
	int attempt,
	AcceptedAnswerKnowledgeDocument document
) {
}
