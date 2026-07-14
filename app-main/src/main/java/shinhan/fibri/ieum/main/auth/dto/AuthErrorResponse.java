package shinhan.fibri.ieum.main.auth.dto;

import java.util.List;

public record AuthErrorResponse(
	String code,
	String message,
	List<FieldError> fieldErrors
) {

	public AuthErrorResponse(String code, String message) {
		this(code, message, List.of());
	}

	public record FieldError(
		String field,
		String message
	) {
	}
}
