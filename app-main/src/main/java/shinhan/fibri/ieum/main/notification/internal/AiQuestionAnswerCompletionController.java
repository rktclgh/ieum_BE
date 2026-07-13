package shinhan.fibri.ieum.main.notification.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ai/question-answer-jobs")
public class AiQuestionAnswerCompletionController {

	private static final String INTERNAL_TOKEN_HEADER = "X-IEUM-Internal-Token";

	private final InternalAiCallbackTokenVerifier tokenVerifier;
	private final AiQuestionAnswerCompletionService service;

	public AiQuestionAnswerCompletionController(
		InternalAiCallbackTokenVerifier tokenVerifier,
		AiQuestionAnswerCompletionService service
	) {
		this.tokenVerifier = tokenVerifier;
		this.service = service;
	}

	@PostMapping("/{questionId}/completed")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void complete(
		@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
		@PathVariable @Positive Long questionId,
		@Valid @RequestBody CompletionRequest request
	) {
		if (!tokenVerifier.matches(token)) {
			throw new InvalidInternalAiTokenException();
		}
		service.complete(questionId, request.answerId());
	}

	public record CompletionRequest(@NotNull @Positive Long answerId) {
	}
}
