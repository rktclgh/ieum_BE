package shinhan.fibri.ieum.main.question.dto;

import java.time.OffsetDateTime;

public record MyQuestionItem(
	Long questionId,
	String title,
	boolean isResolved,
	String thumbnailUrl,
	int answerCount,
	OffsetDateTime createdAt
) {
}
