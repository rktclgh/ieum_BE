package shinhan.fibri.ieum.ai.question.repository;

import java.time.OffsetDateTime;

public record QuestionTaskDispatchSnapshot(
	long questionId,
	QuestionTaskStatus status,
	boolean activeLease,
	boolean cancellationRequested,
	boolean questionDeleted,
	boolean pinDeleted,
	Long answerId,
	OffsetDateTime answerNotificationProcessedAt
) {

	public boolean isCancelledOrDeleted() {
		return status == QuestionTaskStatus.CANCELLED
			|| cancellationRequested
			|| questionDeleted
			|| pinDeleted;
	}

	public boolean hasPendingAnswerCallback() {
		return status == QuestionTaskStatus.COMPLETED
			&& answerId != null
			&& answerNotificationProcessedAt == null;
	}

}
