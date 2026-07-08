package shinhan.fibri.ieum.main.question.dto;

import java.util.List;

public record QuestionDetailResponse(
	Long questionId,
	String title,
	String content,
	boolean isResolved,
	AuthorSummary author,
	List<String> imageUrls,
	List<AnswerItem> answers
) {
}
