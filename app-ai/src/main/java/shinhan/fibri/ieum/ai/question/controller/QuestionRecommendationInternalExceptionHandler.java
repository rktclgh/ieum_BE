package shinhan.fibri.ieum.ai.question.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.ai.question.service.EmbeddingUnavailableException;
import shinhan.fibri.ieum.ai.question.service.InvalidQuestionRecommendationRequestException;

@RestControllerAdvice(assignableTypes = QuestionRecommendationInternalController.class)
public class QuestionRecommendationInternalExceptionHandler {

	@ExceptionHandler(InvalidQuestionRecommendationRequestException.class)
	ResponseEntity<QuestionRecommendationErrorResponse> invalidRequest() {
		return error(HttpStatus.BAD_REQUEST, "invalid_question_recommendation_request", false);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<QuestionRecommendationErrorResponse> unreadableRequest() {
		return error(HttpStatus.BAD_REQUEST, "invalid_question_recommendation_request", false);
	}

	@ExceptionHandler(EmbeddingUnavailableException.class)
	ResponseEntity<QuestionRecommendationErrorResponse> embeddingUnavailable() {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "embedding_unavailable", true);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<QuestionRecommendationErrorResponse> unexpectedFailure() {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_question_recommendation_error", true);
	}

	private ResponseEntity<QuestionRecommendationErrorResponse> error(HttpStatus status, String code, boolean retryable) {
		return ResponseEntity.status(status).body(new QuestionRecommendationErrorResponse(code, retryable));
	}
}
