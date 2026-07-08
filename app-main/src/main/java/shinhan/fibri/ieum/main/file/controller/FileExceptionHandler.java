package shinhan.fibri.ieum.main.file.controller;

import java.io.UncheckedIOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.file.exception.FileNotFoundException;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;

@RestControllerAdvice(assignableTypes = FileController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FileExceptionHandler {

	@ExceptionHandler(InvalidFileRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidFileRequest(InvalidFileRequestException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("INVALID_FILE_REQUEST", exception.getMessage()));
	}

	@ExceptionHandler(FileNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleFileNotFound(FileNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("FILE_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(UncheckedIOException.class)
	public ResponseEntity<AuthErrorResponse> handleStorageIoFailure(UncheckedIOException exception) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
			.body(new AuthErrorResponse("FILE_STORAGE_ERROR", "File storage error"));
	}
}
