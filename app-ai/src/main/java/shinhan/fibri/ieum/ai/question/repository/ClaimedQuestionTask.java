package shinhan.fibri.ieum.ai.question.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClaimedQuestionTask(
	long questionId,
	UUID leaseToken,
	OffsetDateTime leaseUntil,
	int attempts
) {
}
