package shinhan.fibri.ieum.common.ai.job.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import shinhan.fibri.ieum.common.ai.job.AiJobType;

/**
 * {@code ai.accepted-answer.ingest} 라우팅 키 메시지. spec.md §6.5 (2).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcceptedAnswerKnowledgeMessage(
	int schemaVersion,
	String jobId,
	AiJobType jobType,
	long answerId,
	String occurredAt
) {

	public AcceptedAnswerKnowledgeMessage {
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(jobType, "jobType must not be null");
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");
		if (answerId <= 0) {
			throw new IllegalArgumentException("answerId must be positive: " + answerId);
		}
	}
}
