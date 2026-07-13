package shinhan.fibri.ieum.common.ai.question.dto;

import java.util.List;

public record InternalQuestionRecommendationResponse(
	List<InternalQuestionRecommendationItem> items
) {
}
