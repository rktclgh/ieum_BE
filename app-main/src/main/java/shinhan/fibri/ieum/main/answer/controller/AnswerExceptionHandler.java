package shinhan.fibri.ieum.main.answer.controller;

import java.util.Comparator;
import java.util.List;
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
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.exception.AnswerSelectionFinalizedException;
import shinhan.fibri.ieum.main.answer.exception.InvalidAnswerRequestException;
import shinhan.fibri.ieum.main.answer.exception.SelfAcceptanceNotAllowedException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;

@RestControllerAdvice(assignableTypes = AnswerController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AnswerExceptionHandler {

	@ExceptionHandler(QuestionNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleQuestionNotFound(QuestionNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("QUESTION_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(AnswerNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleAnswerNotFound(AnswerNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("ANSWER_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(QuestionForbiddenException.class)
	public ResponseEntity<AuthErrorResponse> handleQuestionForbidden(QuestionForbiddenException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("FORBIDDEN", exception.getMessage()));
	}

	@ExceptionHandler(AnswerSelectionFinalizedException.class)
	public ResponseEntity<AuthErrorResponse> handleAnswerSelectionFinalized(AnswerSelectionFinalizedException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("ANSWER_SELECTION_FINALIZED", exception.getMessage()));
	}

	@ExceptionHandler(SelfAcceptanceNotAllowedException.class)
	public ResponseEntity<AuthErrorResponse> handleSelfAcceptanceNotAllowed(SelfAcceptanceNotAllowedException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("SELF_ACCEPT_NOT_ALLOWED", exception.getMessage()));
	}

	@ExceptionHandler(InvalidAnswerRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidAnswerRequest(InvalidAnswerRequestException exception) {
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

		return java.util.stream.Stream
			.concat(fieldErrors.stream(), globalErrors.stream())
			.sorted(Comparator.comparing(AuthErrorResponse.FieldError::field))
			.toList();
	}

	private AuthErrorResponse.FieldError toGlobalError(ObjectError error) {
		return new AuthErrorResponse.FieldError(error.getObjectName(), error.getDefaultMessage());
	}
}
