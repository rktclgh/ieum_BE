package shinhan.fibri.ieum.main.notification.controller;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.notification.exception.InvalidNotificationCursorException;
import shinhan.fibri.ieum.main.notification.exception.NotificationNotFoundException;

@RestControllerAdvice(assignableTypes = NotificationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationExceptionHandler {

	@ExceptionHandler(InvalidNotificationCursorException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidCursor(InvalidNotificationCursorException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				"INVALID_CURSOR",
				exception.getMessage(),
				List.of(new AuthErrorResponse.FieldError("cursor", exception.getMessage()))
			));
	}

	@ExceptionHandler(NotificationNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleNotificationNotFound(NotificationNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("NOTIFICATION_NOT_FOUND", exception.getMessage()));
	}
}
