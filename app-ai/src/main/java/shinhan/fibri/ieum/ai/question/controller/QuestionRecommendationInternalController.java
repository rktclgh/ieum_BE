package shinhan.fibri.ieum.ai.question.controller;

import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.ai.question.service.InvalidQuestionRecommendationRequestException;
import shinhan.fibri.ieum.ai.question.service.QuestionRecommendationService;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationRequest;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationResponse;
import shinhan.fibri.ieum.common.ai.question.dto.QuestionRecommendationLocation;

@RestController
@RequestMapping("/ai/v1/internal/questions")
@ConditionalOnProperty(prefix = "app.ai.features", name = "question-recommendations-enabled", havingValue = "true")
public class QuestionRecommendationInternalController {

	private static final int MAX_TITLE_LENGTH = 200;
	private static final int MAX_CONTENT_LENGTH = 5000;
	private static final int MAX_CANDIDATE_LIMIT = 20;
	private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
	private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
	private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
	private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

	private final QuestionRecommendationService recommendationService;

	public QuestionRecommendationInternalController(QuestionRecommendationService recommendationService) {
		this.recommendationService = recommendationService;
	}

	@PostMapping("/recommendations")
	public InternalQuestionRecommendationResponse recommend(@RequestBody InternalQuestionRecommendationRequest request) {
		validate(request);
		return recommendationService.recommend(request);
	}

	private void validate(InternalQuestionRecommendationRequest request) {
		if (request == null
			|| hasInvalidText(request.title(), MAX_TITLE_LENGTH)
			|| hasInvalidText(request.content(), MAX_CONTENT_LENGTH)
			|| request.candidateLimit() < 1
			|| request.candidateLimit() > MAX_CANDIDATE_LIMIT
			|| hasInvalidLocation(request.location())) {
			throw new InvalidQuestionRecommendationRequestException();
		}
	}

	private boolean hasInvalidText(String value, int maxLength) {
		return value == null || value.strip().isEmpty() || value.strip().length() > maxLength;
	}

	private boolean hasInvalidLocation(QuestionRecommendationLocation location) {
		return location == null
			|| isOutsideRange(location.lat(), MIN_LATITUDE, MAX_LATITUDE)
			|| isOutsideRange(location.lng(), MIN_LONGITUDE, MAX_LONGITUDE)
			|| location.address() == null;
	}

	private boolean isOutsideRange(BigDecimal value, BigDecimal min, BigDecimal max) {
		return value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0;
	}
}
