package shinhan.fibri.ieum.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.support.HttpRequestPaths;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
	private static final Resource NEXT_NOT_FOUND_PAGE = new ClassPathResource("static/error/404.html");

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<?> handleNoResourceFound(
		NoResourceFoundException exception,
		HttpServletRequest request
	) {
		if (!HttpRequestPaths.isBackendOrOperations(request) && NEXT_NOT_FOUND_PAGE.exists()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.contentType(MediaType.TEXT_HTML)
				.body(NEXT_NOT_FOUND_PAGE);
		}
		return jsonNotFound();
	}

	private static ResponseEntity<AuthErrorResponse> jsonNotFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("NOT_FOUND", "Resource not found"));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<AuthErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
			.body(new AuthErrorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<AuthErrorResponse> handleUnsupportedMediaType(
		HttpMediaTypeNotSupportedException exception
	) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			.body(new AuthErrorResponse("UNSUPPORTED_MEDIA_TYPE", "Unsupported media type"));
	}

	@ExceptionHandler({
		HttpMessageNotReadableException.class,
		MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<AuthErrorResponse> handleBadRequest(Exception exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("BAD_REQUEST", "Bad request"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<AuthErrorResponse> handleUnexpectedException(Exception exception) {
		log.error("Unhandled exception", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(new AuthErrorResponse("INTERNAL_SERVER_ERROR", "Internal server error"));
	}
}
