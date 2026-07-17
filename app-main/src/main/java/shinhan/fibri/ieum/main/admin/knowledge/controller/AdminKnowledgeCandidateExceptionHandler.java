package shinhan.fibri.ieum.main.admin.knowledge.controller;

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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.InvalidKnowledgeCandidateCursorException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.InvalidKnowledgeCandidateStatusException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateConcurrentlyChangedException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateNotFoundException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateSourceIneligibleException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;

@RestControllerAdvice(assignableTypes = AdminKnowledgeCandidateController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminKnowledgeCandidateExceptionHandler {

	@ExceptionHandler(KnowledgeCandidateNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleNotFound(KnowledgeCandidateNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("KNOWLEDGE_CANDIDATE_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(KnowledgeCandidateConcurrentlyChangedException.class)
	public ResponseEntity<AuthErrorResponse> handleConcurrentChange(
		KnowledgeCandidateConcurrentlyChangedException exception
	) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("KNOWLEDGE_CANDIDATE_CONCURRENTLY_CHANGED", exception.getMessage()));
	}

	@ExceptionHandler(KnowledgeCandidateSourceIneligibleException.class)
	public ResponseEntity<AuthErrorResponse> handleSourceIneligible(KnowledgeCandidateSourceIneligibleException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("KNOWLEDGE_CANDIDATE_SOURCE_INELIGIBLE", exception.getMessage()));
	}

	@ExceptionHandler(InvalidKnowledgeCandidateCursorException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidCursor(InvalidKnowledgeCandidateCursorException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				"INVALID_CURSOR",
				exception.getMessage(),
				List.of(new AuthErrorResponse.FieldError("cursor", exception.getMessage()))
			));
	}

	@ExceptionHandler(InvalidKnowledgeCandidateStatusException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidStatus(InvalidKnowledgeCandidateStatusException exception) {
		return validationFailure(List.of(new AuthErrorResponse.FieldError("status", exception.getMessage())));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<AuthErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
		return validationFailure(List.of(new AuthErrorResponse.FieldError("request", exception.getMessage())));
	}

	@ExceptionHandler(BindException.class)
	public ResponseEntity<AuthErrorResponse> handleBindException(BindException exception) {
		return validationFailure(toFieldErrors(exception));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception) {
		return validationFailure(toFieldErrors(exception));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<AuthErrorResponse> handleMethodValidation(HandlerMethodValidationException exception) {
		List<AuthErrorResponse.FieldError> fieldErrors = exception.getParameterValidationResults()
			.stream()
			.flatMap(result -> {
				String field = result.getMethodParameter().getParameterName();
				String safeField = field == null ? "parameter" : field;
				return result.getResolvableErrors()
					.stream()
					.map(error -> new AuthErrorResponse.FieldError(safeField, error.getDefaultMessage()));
			})
			.sorted(Comparator.comparing(AuthErrorResponse.FieldError::field))
			.toList();
		return validationFailure(fieldErrors);
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
		return java.util.stream.Stream.concat(fieldErrors.stream(), globalErrors.stream())
			.sorted(Comparator.comparing(AuthErrorResponse.FieldError::field))
			.toList();
	}

	private AuthErrorResponse.FieldError toGlobalError(ObjectError error) {
		return new AuthErrorResponse.FieldError(error.getObjectName(), error.getDefaultMessage());
	}
}
