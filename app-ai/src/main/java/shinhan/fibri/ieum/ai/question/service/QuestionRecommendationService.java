package shinhan.fibri.ieum.ai.question.service;

import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationRequest;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationResponse;

public interface QuestionRecommendationService {

	InternalQuestionRecommendationResponse recommend(InternalQuestionRecommendationRequest request);
}
