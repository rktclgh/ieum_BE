package shinhan.fibri.ieum.main.notification.internal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;

@RestControllerAdvice(assignableTypes = AiQuestionAnswerCompletionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiQuestionAnswerCompletionExceptionHandler {

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HttpMessageNotReadableException.class,
		HandlerMethodValidationException.class
	})
	public ResponseEntity<AuthErrorResponse> handleInvalidRequest(Exception exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("AI_ANSWER_JOB_INVALID_REQUEST", "Invalid request"));
	}

	@ExceptionHandler(InvalidInternalAiTokenException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidToken(InvalidInternalAiTokenException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new AuthErrorResponse("INVALID_INTERNAL_AI_TOKEN", exception.getMessage()));
	}

	@ExceptionHandler(AiQuestionAnswerTicketNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleNotFound(AiQuestionAnswerTicketNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("AI_ANSWER_JOB_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(AiQuestionAnswerCompletionConflictException.class)
	public ResponseEntity<AuthErrorResponse> handleConflict(AiQuestionAnswerCompletionConflictException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("AI_ANSWER_JOB_CONFLICT", exception.getMessage()));
	}
}
