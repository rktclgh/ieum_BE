package shinhan.fibri.ieum.main.notification.push;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;

@RestControllerAdvice(assignableTypes = WebPushSubscriptionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebPushSubscriptionExceptionHandler {

	@ExceptionHandler(WebPushAuthenticationRequiredException.class)
	public ResponseEntity<AuthErrorResponse> handleAuthenticationRequired(
		WebPushAuthenticationRequiredException exception
	) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new AuthErrorResponse("AUTHENTICATION_REQUIRED", exception.getMessage()));
	}

	@ExceptionHandler(InvalidWebPushSubscriptionException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidSubscription(
		InvalidWebPushSubscriptionException exception
	) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				"VALIDATION_FAILED",
				"Request validation failed",
				List.of(new AuthErrorResponse.FieldError(exception.field(), exception.getMessage()))
			));
	}

	@ExceptionHandler(WebPushDisabledException.class)
	public ResponseEntity<AuthErrorResponse> handleDisabled(WebPushDisabledException exception) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(new AuthErrorResponse("WEB_PUSH_DISABLED", exception.getMessage()));
	}
}
