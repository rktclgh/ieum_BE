package shinhan.fibri.ieum.main.question.controller;

import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.pin.exception.InvalidPinRequestException;
import shinhan.fibri.ieum.main.question.exception.InvalidQuestionRequestException;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.exception.QuestionResolvedException;

@RestControllerAdvice(assignableTypes = QuestionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuestionExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(QuestionExceptionHandler.class);

	@ExceptionHandler(QuestionNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleQuestionNotFound(QuestionNotFoundException exception) {
		log.warn("Question not found: {}", exception.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("QUESTION_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(QuestionForbiddenException.class)
	public ResponseEntity<AuthErrorResponse> handleQuestionForbidden(QuestionForbiddenException exception) {
		log.warn("Question access forbidden: {}", exception.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("FORBIDDEN", exception.getMessage()));
	}

	@ExceptionHandler(QuestionResolvedException.class)
	public ResponseEntity<AuthErrorResponse> handleQuestionResolved(QuestionResolvedException exception) {
		log.warn("Resolved question cannot be edited: {}", exception.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("QUESTION_RESOLVED", exception.getMessage()));
	}

	@ExceptionHandler(InvalidQuestionRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidQuestionRequest(InvalidQuestionRequestException exception) {
		log.warn("Invalid question request [{}] field={}: {}",
			exception.code(), exception.field(), exception.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				exception.code(),
				exception.getMessage(),
				List.of(new AuthErrorResponse.FieldError(exception.field(), exception.getMessage()))
			));
	}

	@ExceptionHandler(InvalidPinRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidPinRequest(InvalidPinRequestException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				exception.code(),
				exception.getMessage(),
				List.of(new AuthErrorResponse.FieldError(exception.field(), exception.getMessage()))
			));
	}

	@ExceptionHandler(BindException.class)
	public ResponseEntity<AuthErrorResponse> handleBindException(BindException exception) {
		return validationFailure(toFieldErrors(exception));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception) {
		return validationFailure(toFieldErrors(exception));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<AuthErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
		return validationFailure(List.of(new AuthErrorResponse.FieldError(exception.getName(), "Invalid value")));
	}

	private ResponseEntity<AuthErrorResponse> validationFailure(List<AuthErrorResponse.FieldError> fieldErrors) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", "Request validation failed", fieldErrors));
	}

	private List<AuthErrorResponse.FieldError> toFieldErrors(BindException exception) {
		List<AuthErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(fieldError -> new AuthErrorResponse.FieldError(
				fieldError.getField(),
				fieldError.getDefaultMessage()
			))
			.toList();
		List<AuthErrorResponse.FieldError> globalErrors = exception.getBindingResult()
			.getGlobalErrors()
			.stream()
			.map(this::toGlobalError)
			.toList();

		List<AuthErrorResponse.FieldError> validationErrors = java.util.stream.Stream
			.concat(fieldErrors.stream(), globalErrors.stream())
			.sorted(Comparator.comparing(AuthErrorResponse.FieldError::field))
			.toList();

		return validationErrors;
	}

	private AuthErrorResponse.FieldError toGlobalError(ObjectError error) {
		return new AuthErrorResponse.FieldError(error.getObjectName(), error.getDefaultMessage());
	}
}
