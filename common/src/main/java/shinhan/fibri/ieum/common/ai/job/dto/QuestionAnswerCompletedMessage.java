package shinhan.fibri.ieum.common.ai.job.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import shinhan.fibri.ieum.common.ai.job.AiJobType;

/**
 * {@code ai.question-answer.completed} 라우팅 키 메시지. spec.md §6.5 (3).
 * {@code answer_outcome = insufficient_evidence} 처럼 {@code answer_id IS NULL} 인 경우는
 * 이 메시지를 만들지 않는다 — 생성 시점부터 answerId 는 항상 양수다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuestionAnswerCompletedMessage(
	int schemaVersion,
	String jobId,
	AiJobType jobType,
	long questionId,
	long answerId,
	String occurredAt
) {

	public QuestionAnswerCompletedMessage {
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(jobType, "jobType must not be null");
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");
		if (questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive: " + questionId);
		}
		if (answerId <= 0) {
			throw new IllegalArgumentException("answerId must be positive: " + answerId);
		}
	}
}
