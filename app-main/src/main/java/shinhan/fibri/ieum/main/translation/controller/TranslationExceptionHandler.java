package shinhan.fibri.ieum.main.translation.controller;

import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import shinhan.fibri.ieum.main.translation.service.TranslationNotAvailableException;
import shinhan.fibri.ieum.main.translation.service.TranslationProviderUnavailableException;
import shinhan.fibri.ieum.main.translation.service.TranslationRateLimitedException;

@RestControllerAdvice(assignableTypes = TranslationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TranslationExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		return validationFailure(toFieldErrors(exception));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<AuthErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception) {
		return validationFailure(List.of(new AuthErrorResponse.FieldError("body", "Request body is required")));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<AuthErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
		return validationFailure(List.of(new AuthErrorResponse.FieldError(exception.getName(), "Invalid value")));
	}

	@ExceptionHandler(AnswerNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleAnswerNotFound(AnswerNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("ANSWER_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(ReportMessageNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleMessageNotFound(ReportMessageNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("REPORT_MESSAGE_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(TranslationNotAvailableException.class)
	public ResponseEntity<AuthErrorResponse> handleNotAvailable(TranslationNotAvailableException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("TRANSLATION_NOT_AVAILABLE", exception.getMessage()));
	}

	@ExceptionHandler(TranslationRateLimitedException.class)
	public ResponseEntity<AuthErrorResponse> handleRateLimited(TranslationRateLimitedException exception) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.body(new AuthErrorResponse("TRANSLATION_RATE_LIMITED", exception.getMessage()));
	}

	@ExceptionHandler(TranslationProviderUnavailableException.class)
	public ResponseEntity<AuthErrorResponse> handleProviderUnavailable(
		TranslationProviderUnavailableException exception
	) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(new AuthErrorResponse("TRANSLATION_UNAVAILABLE", exception.getMessage()));
	}

	private ResponseEntity<AuthErrorResponse> validationFailure(List<AuthErrorResponse.FieldError> fieldErrors) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", "Request validation failed", fieldErrors));
	}

	private List<AuthErrorResponse.FieldError> toFieldErrors(BindException exception) {
		return exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new AuthErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.sorted(Comparator.comparing(AuthErrorResponse.FieldError::field))
			.toList();
	}
}
