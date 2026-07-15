package shinhan.fibri.ieum.main.admin.content.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
import shinhan.fibri.ieum.main.admin.content.exception.UnsupportedContentTypeException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;

@RestControllerAdvice(assignableTypes = AdminContentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminContentExceptionHandler {

	@ExceptionHandler(ContentNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleContentNotFound(ContentNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("CONTENT_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(UnsupportedContentTypeException.class)
	public ResponseEntity<AuthErrorResponse> handleUnsupportedContentType(UnsupportedContentTypeException exception) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
			.body(new AuthErrorResponse("CONTENT_TYPE_NOT_IMPLEMENTED", exception.getMessage()));
	}
}
