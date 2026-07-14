package shinhan.fibri.ieum.main.question.dto;

import java.time.OffsetDateTime;
import java.util.List;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;

public record QuestionDetailResponse(
	Long questionId,
	String title,
	String content,
	boolean isResolved,
	AuthorSummary author,
	LocationSnapshot location,
	List<String> imageUrls,
	List<AnswerItem> answers,
	OffsetDateTime createdAt,
	OffsetDateTime updatedAt
) {
}
