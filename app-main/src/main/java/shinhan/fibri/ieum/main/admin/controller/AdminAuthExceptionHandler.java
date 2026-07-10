package shinhan.fibri.ieum.main.admin.controller;

import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailNotVerifiedException;
import shinhan.fibri.ieum.main.auth.exception.InvalidCredentialsException;
import shinhan.fibri.ieum.main.auth.exception.SuspendedUserException;

@RestControllerAdvice(assignableTypes = AdminAuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminAuthExceptionHandler {

	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidCredentials(InvalidCredentialsException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new AuthErrorResponse("INVALID_CREDENTIALS", exception.getMessage()));
	}

	@ExceptionHandler(EmailNotVerifiedException.class)
	public ResponseEntity<AuthErrorResponse> handleEmailNotVerified(EmailNotVerifiedException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("EMAIL_NOT_VERIFIED", exception.getMessage()));
	}

	@ExceptionHandler(SuspendedUserException.class)
	public ResponseEntity<AuthErrorResponse> handleSuspendedUser(SuspendedUserException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("SUSPENDED_USER", exception.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception) {
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

		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", "Request validation failed", validationErrors));
	}

	private AuthErrorResponse.FieldError toGlobalError(ObjectError error) {
		return new AuthErrorResponse.FieldError(error.getObjectName(), error.getDefaultMessage());
	}
}
