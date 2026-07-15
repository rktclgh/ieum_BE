package shinhan.fibri.ieum.main.admin.user.controller;

import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotDeleteSelfException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotHardDeleteUserException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotSanctionAdminException;
import shinhan.fibri.ieum.main.admin.user.exception.HardDeleteConfirmationMismatchException;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidAdminCursorException;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidSanctionRequestException;
import shinhan.fibri.ieum.main.admin.user.exception.SanctionAlreadyActiveException;
import shinhan.fibri.ieum.main.admin.user.exception.UserNotSanctionedException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;

@RestControllerAdvice(assignableTypes = AdminUserController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminUserExceptionHandler {

	@ExceptionHandler(InvalidAdminCursorException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidCursor(InvalidAdminCursorException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				"INVALID_CURSOR",
				exception.getMessage(),
				List.of(new AuthErrorResponse.FieldError("cursor", exception.getMessage()))
			));
	}

	@ExceptionHandler(InvalidSanctionRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidSanctionRequest(InvalidSanctionRequestException exception) {
		return validationFailure(List.of(new AuthErrorResponse.FieldError(exception.field(), exception.getMessage())));
	}

	@ExceptionHandler(HardDeleteConfirmationMismatchException.class)
	public ResponseEntity<AuthErrorResponse> handleHardDeleteConfirmationMismatch(
		HardDeleteConfirmationMismatchException exception
	) {
		return validationFailure(List.of(new AuthErrorResponse.FieldError(exception.field(), exception.getMessage())));
	}

	@ExceptionHandler(AdminUserNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleUserNotFound(AdminUserNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("USER_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(CannotSanctionAdminException.class)
	public ResponseEntity<AuthErrorResponse> handleCannotSanctionAdmin(CannotSanctionAdminException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("CANNOT_SANCTION_ADMIN", exception.getMessage()));
	}

	@ExceptionHandler(CannotHardDeleteUserException.class)
	public ResponseEntity<AuthErrorResponse> handleCannotHardDeleteUser(CannotHardDeleteUserException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("CANNOT_HARD_DELETE_USER", exception.getMessage()));
	}

	@ExceptionHandler(CannotDeleteSelfException.class)
	public ResponseEntity<AuthErrorResponse> handleCannotDeleteSelf(CannotDeleteSelfException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("CANNOT_DELETE_SELF", exception.getMessage()));
	}

	@ExceptionHandler(SanctionAlreadyActiveException.class)
	public ResponseEntity<AuthErrorResponse> handleSanctionAlreadyActive(SanctionAlreadyActiveException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("SANCTION_ALREADY_ACTIVE", "User already has an active sanction"));
	}

	@ExceptionHandler(UserNotSanctionedException.class)
	public ResponseEntity<AuthErrorResponse> handleUserNotSanctioned(UserNotSanctionedException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("USER_NOT_SANCTIONED", exception.getMessage()));
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

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<AuthErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception) {
		return validationFailure(List.of(new AuthErrorResponse.FieldError("body", "Request body is required")));
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
