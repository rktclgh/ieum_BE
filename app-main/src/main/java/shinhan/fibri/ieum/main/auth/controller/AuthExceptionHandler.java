package shinhan.fibri.ieum.main.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.ObjectError;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailCodeRateLimitedException;
import shinhan.fibri.ieum.main.auth.exception.EmailNotVerifiedException;
import shinhan.fibri.ieum.main.auth.exception.EmailTakenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidCredentialsException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationCodeException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationTokenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidSignupFieldException;
import shinhan.fibri.ieum.main.auth.exception.InvalidRefreshTokenException;
import shinhan.fibri.ieum.main.auth.exception.NicknameTakenException;
import shinhan.fibri.ieum.main.auth.exception.RefreshTokenReusedException;
import shinhan.fibri.ieum.main.auth.exception.SuspendedUserException;

import java.util.Comparator;
import java.util.List;

@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
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

	@ExceptionHandler(InvalidRefreshTokenException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new AuthErrorResponse("INVALID_REFRESH_TOKEN", exception.getMessage()));
	}

	@ExceptionHandler(MissingRequestCookieException.class)
	public ResponseEntity<AuthErrorResponse> handleMissingRequestCookie(MissingRequestCookieException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new AuthErrorResponse("INVALID_REFRESH_TOKEN", "Invalid refresh token"));
	}

	@ExceptionHandler(RefreshTokenReusedException.class)
	public ResponseEntity<AuthErrorResponse> handleRefreshTokenReused(RefreshTokenReusedException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new AuthErrorResponse("REFRESH_TOKEN_REUSED", exception.getMessage()));
	}

	@ExceptionHandler(InvalidSignupFieldException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidSignupField(InvalidSignupFieldException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				"VALIDATION_FAILED",
				"Request validation failed",
				List.of(new AuthErrorResponse.FieldError(exception.field(), exception.getMessage()))
			));
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
