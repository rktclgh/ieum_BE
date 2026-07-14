package shinhan.fibri.ieum.main.inquiry.controller;

import java.util.List;
import java.util.concurrent.CompletionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.inquiry.exception.SuspendedUserInquiryRateLimitedException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@RestControllerAdvice(assignableTypes = InquiryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InquiryExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		List<AuthErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new AuthErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.toList();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", "Request validation failed", fieldErrors));
	}

	@ExceptionHandler(UserNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleUserNotFound(UserNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("USER_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(SuspendedUserInquiryRateLimitedException.class)
	public ResponseEntity<AuthErrorResponse> handleRateLimited(SuspendedUserInquiryRateLimitedException exception) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header("Retry-After", "60")
			.body(new AuthErrorResponse("INQUIRY_RATE_LIMITED", exception.getMessage()));
	}

	@ExceptionHandler(MailException.class)
	public ResponseEntity<AuthErrorResponse> handleMailDeliveryFailure(MailException exception) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
			.body(new AuthErrorResponse("INQUIRY_MAIL_DELIVERY_FAILED", "Failed to send inquiry mail"));
	}

	@ExceptionHandler(CompletionException.class)
	public ResponseEntity<AuthErrorResponse> handleCompletionException(CompletionException exception) {
		if (hasCause(exception, MailException.class)) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(new AuthErrorResponse("INQUIRY_MAIL_DELIVERY_FAILED", "Failed to send inquiry mail"));
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(new AuthErrorResponse("INTERNAL_SERVER_ERROR", "Internal server error"));
	}

	private boolean hasCause(Throwable exception, Class<? extends Throwable> causeType) {
		Throwable current = exception;
		while (current != null) {
			if (causeType.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
