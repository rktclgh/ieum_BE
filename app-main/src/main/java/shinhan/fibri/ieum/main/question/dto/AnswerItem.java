package shinhan.fibri.ieum.main.question.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AnswerItem(
	Long answerId,
	boolean isAi,
	AuthorSummary author,
	String content,
	boolean isAccepted,
	OffsetDateTime createdAt,
	List<String> imageUrls
) {
}
