package shinhan.fibri.ieum.ai.question.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.ai.config.QuestionAnswerDispatchProperties;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchResult;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchService;

@RestController
@RequestMapping("/ai/v1/internal/question-answer-jobs")
public class QuestionAnswerJobDispatchInternalController {

	private final QuestionAnswerJobDispatchService dispatchService;
	private final int retryAfterSeconds;

	@Autowired
	public QuestionAnswerJobDispatchInternalController(
		QuestionAnswerJobDispatchService dispatchService,
		QuestionAnswerDispatchProperties properties
	) {
		this(dispatchService, properties.retryAfterSeconds());
	}

	QuestionAnswerJobDispatchInternalController(
		QuestionAnswerJobDispatchService dispatchService,
		int retryAfterSeconds
	) {
		this.dispatchService = dispatchService;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	@PostMapping("/{questionId}/dispatch")
	public ResponseEntity<QuestionAnswerJobDispatchResponse> dispatch(@PathVariable long questionId) {
		QuestionAnswerJobDispatchResult result = dispatchService.dispatch(questionId);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(result.httpStatus());
		if (result.retryAfterRequired()) {
			response.header(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfterSeconds));
		}
		return response.body(new QuestionAnswerJobDispatchResponse(result.responseStatus()));
	}
}
