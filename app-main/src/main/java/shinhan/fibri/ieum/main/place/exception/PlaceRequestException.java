package shinhan.fibri.ieum.main.place.exception;

import org.springframework.http.HttpStatus;

public class PlaceRequestException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final String field;

	public PlaceRequestException(HttpStatus status, String code, String field, String message) {
		super(message);
		this.status = status;
		this.code = code;
		this.field = field;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public String field() {
		return field;
	}
}
