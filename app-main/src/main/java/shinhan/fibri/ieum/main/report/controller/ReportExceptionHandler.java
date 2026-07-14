package shinhan.fibri.ieum.main.report.controller;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import tools.jackson.databind.exc.MismatchedInputException;

@RestControllerAdvice(assignableTypes = {ReportController.class, AnswerReportController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReportExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		List<AuthErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new AuthErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.toList();
		return validationFailure(fieldErrors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<AuthErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception) {
		return validationFailure(unreadableFieldErrors(exception));
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

	@ExceptionHandler(NotRoomMemberException.class)
	public ResponseEntity<AuthErrorResponse> handleNotRoomMember(NotRoomMemberException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("NOT_ROOM_MEMBER", exception.getMessage()));
	}

	private List<AuthErrorResponse.FieldError> unreadableFieldErrors(HttpMessageNotReadableException exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
				String field = mismatch.getPath().get(mismatch.getPath().size() - 1).getPropertyName();
				if (field != null && !field.isBlank()) {
					return List.of(new AuthErrorResponse.FieldError(field, "Invalid value"));
				}
			}
			Throwable next = current.getCause();
			if (next == current) {
				break;
			}
			current = next;
		}
		return List.of();
	}

	private ResponseEntity<AuthErrorResponse> validationFailure(List<AuthErrorResponse.FieldError> fieldErrors) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", "Request validation failed", fieldErrors));
	}
}
