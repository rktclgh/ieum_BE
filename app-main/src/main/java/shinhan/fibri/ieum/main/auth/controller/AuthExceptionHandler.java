package shinhan.fibri.ieum.main.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.ObjectError;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailCodeRateLimitedException;
import shinhan.fibri.ieum.main.auth.exception.EmailTakenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationCodeException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationTokenException;
import shinhan.fibri.ieum.main.auth.exception.NicknameTakenException;

import java.util.Comparator;
import java.util.List;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

	@ExceptionHandler(InvalidEmailVerificationCodeException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidEmailVerificationCode(
		InvalidEmailVerificationCodeException exception
	) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("INVALID_EMAIL_VERIFICATION_CODE", exception.getMessage()));
	}

	@ExceptionHandler(InvalidEmailVerificationTokenException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidEmailVerificationToken(
		InvalidEmailVerificationTokenException exception
	) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("INVALID_EMAIL_VERIFICATION_TOKEN", exception.getMessage()));
	}

	@ExceptionHandler(EmailTakenException.class)
	public ResponseEntity<AuthErrorResponse> handleEmailTaken(EmailTakenException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("EMAIL_TAKEN", exception.getMessage()));
	}

	@ExceptionHandler(EmailCodeRateLimitedException.class)
	public ResponseEntity<AuthErrorResponse> handleEmailCodeRateLimited(
		EmailCodeRateLimitedException exception
	) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.body(new AuthErrorResponse("EMAIL_CODE_RATE_LIMITED", exception.getMessage()));
	}

	@ExceptionHandler(NicknameTakenException.class)
	public ResponseEntity<AuthErrorResponse> handleNicknameTaken(NicknameTakenException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("NICKNAME_TAKEN", exception.getMessage()));
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
