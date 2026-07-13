package shinhan.fibri.ieum.common.ai.question.dto;

public record InternalQuestionRecommendationRequest(
	String title,
	String content,
	QuestionRecommendationLocation location,
	int candidateLimit
) {
}
