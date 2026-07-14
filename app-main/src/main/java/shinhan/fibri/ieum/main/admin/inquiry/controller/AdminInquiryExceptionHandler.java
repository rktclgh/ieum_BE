package shinhan.fibri.ieum.main.admin.inquiry.controller;

import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryAlreadyAnsweredException;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidAdminCursorException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;

@RestControllerAdvice(assignableTypes = AdminInquiryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminInquiryExceptionHandler {

	@ExceptionHandler(InvalidAdminCursorException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidCursor(InvalidAdminCursorException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				"INVALID_CURSOR",
				exception.getMessage(),
				List.of(new AuthErrorResponse.FieldError("cursor", exception.getMessage()))
			));
	}

	@ExceptionHandler(InquiryNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleInquiryNotFound(InquiryNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("INQUIRY_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(InquiryAlreadyAnsweredException.class)
	public ResponseEntity<AuthErrorResponse> handleAlreadyAnswered(InquiryAlreadyAnsweredException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("INQUIRY_ALREADY_ANSWERED", exception.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		List<AuthErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new AuthErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.sorted(Comparator.comparing(AuthErrorResponse.FieldError::field))
			.toList();
		return validationFailure(fieldErrors);
	}

	@ExceptionHandler(BindException.class)
	public ResponseEntity<AuthErrorResponse> handleBind(BindException exception) {
		List<AuthErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new AuthErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
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
}
