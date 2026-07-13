package shinhan.fibri.ieum.main.ai.client;

import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationRequest;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationResponse;

public interface AiQuestionRecommendationClient {

	InternalQuestionRecommendationResponse recommend(InternalQuestionRecommendationRequest request);
}
