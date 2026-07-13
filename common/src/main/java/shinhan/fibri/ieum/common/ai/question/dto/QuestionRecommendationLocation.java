package shinhan.fibri.ieum.common.ai.question.dto;

import java.math.BigDecimal;

public record QuestionRecommendationLocation(
	BigDecimal lat,
	BigDecimal lng,
	String address,
	String detailAddress,
	String label
) {
}
