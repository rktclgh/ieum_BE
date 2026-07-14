package shinhan.fibri.ieum.main.place.controller;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.place.exception.PlaceProviderException;
import shinhan.fibri.ieum.main.place.exception.PlaceRateLimitedException;
import shinhan.fibri.ieum.main.place.exception.PlaceProviderBusyException;
import shinhan.fibri.ieum.main.place.exception.PlaceRequestException;

@RestControllerAdvice(assignableTypes = PlaceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlaceExceptionHandler {

	@ExceptionHandler(PlaceRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleRequest(PlaceRequestException exception) {
		return ResponseEntity.status(exception.status())
			.body(new AuthErrorResponse(
				exception.code(),
				exception.getMessage(),
				List.of(new AuthErrorResponse.FieldError(exception.field(), exception.getMessage()))
			));
	}

	@ExceptionHandler(PlaceProviderException.class)
	public ResponseEntity<AuthErrorResponse> handleProvider(PlaceProviderException exception) {
		return ResponseEntity.status(exception.status())
			.body(new AuthErrorResponse(exception.code(), exception.getMessage()));
	}

	@ExceptionHandler(PlaceRateLimitedException.class)
	public ResponseEntity<AuthErrorResponse> handleRateLimited(PlaceRateLimitedException exception) {
		return ResponseEntity.status(429)
			.header("Retry-After", "60")
			.body(new AuthErrorResponse("PLACE_RATE_LIMITED", exception.getMessage()));
	}

	@ExceptionHandler(PlaceProviderBusyException.class)
	public ResponseEntity<AuthErrorResponse> handleProviderBusy(PlaceProviderBusyException exception) {
		return ResponseEntity.status(503)
			.body(new AuthErrorResponse("PLACE_PROVIDER_BUSY", exception.getMessage()));
	}
}
