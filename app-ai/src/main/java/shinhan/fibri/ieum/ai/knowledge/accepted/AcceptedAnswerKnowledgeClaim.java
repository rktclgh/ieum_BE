package shinhan.fibri.ieum.ai.knowledge.accepted;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record AcceptedAnswerKnowledgeClaim(
	long sourceId,
	long questionId,
	long answerId,
	UUID ingestionToken,
	OffsetDateTime leaseUntil,
	int attempt,
	AcceptedAnswerKnowledgeDocument document
) {

	public AcceptedAnswerKnowledgeClaim {
		if (sourceId <= 0 || questionId <= 0 || answerId <= 0) {
			throw new IllegalArgumentException("sourceId, questionId and answerId must be positive");
		}
		Objects.requireNonNull(ingestionToken, "ingestionToken must not be null");
		Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
		if (attempt <= 0) {
			throw new IllegalArgumentException("attempt must be positive");
		}
		Objects.requireNonNull(document, "document must not be null");
	}
}
