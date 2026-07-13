package shinhan.fibri.ieum.common.ai.question.dto;

import java.math.BigDecimal;

public record InternalQuestionRecommendationItem(
	long questionId,
	long authorId,
	String title,
	BigDecimal relevanceScore,
	String geoScope,
	boolean isResolved,
	RecommendedAcceptedAnswer acceptedAnswer
) {
}
