package shinhan.fibri.ieum.common.ai.job.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import java.util.Set;
import shinhan.fibri.ieum.common.ai.job.AiJobType;

/**
 * {@code ai.question-answer.dispatch} 라우팅 키 메시지. spec.md §6.5 (1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuestionAnswerDispatchMessage(
	int schemaVersion,
	String jobId,
	AiJobType jobType,
	long questionId,
	String reason,
	String occurredAt
) {

	private static final Set<String> ALLOWED_REASONS = Set.of("created", "regenerated");

	public QuestionAnswerDispatchMessage {
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(jobType, "jobType must not be null");
		Objects.requireNonNull(reason, "reason must not be null");
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");
		if (questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive: " + questionId);
		}
		if (!ALLOWED_REASONS.contains(reason)) {
			throw new IllegalArgumentException("reason must be one of " + ALLOWED_REASONS + " but was: " + reason);
		}
	}
}
