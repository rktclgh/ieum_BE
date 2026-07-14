package shinhan.fibri.ieum.main.notification.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.notification.sse.SseAuthenticationRequiredException;

@RestControllerAdvice(assignableTypes = SseController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SseExceptionHandler {

	@ExceptionHandler(SseAuthenticationRequiredException.class)
	public ResponseEntity<AuthErrorResponse> handleAuthenticationRequired(SseAuthenticationRequiredException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new AuthErrorResponse("AUTHENTICATION_REQUIRED", exception.getMessage()));
	}
}
