package shinhan.fibri.ieum.main.notification.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.notification.sse.SseAuthenticationRequiredException;

@RestControllerAdvice(assignableTypes = SseController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SseExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(SseExceptionHandler.class);

	@ExceptionHandler(SseAuthenticationRequiredException.class)
	public ResponseEntity<Void> handleAuthenticationRequired(SseAuthenticationRequiredException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.build();
	}

	@ExceptionHandler(CannotGetJdbcConnectionException.class)
	public ResponseEntity<Void> handleDatabaseUnavailable(CannotGetJdbcConnectionException exception) {
		log.warn("event=sse_subscription_db_unavailable failureType={}", exception.getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.build();
	}
}
