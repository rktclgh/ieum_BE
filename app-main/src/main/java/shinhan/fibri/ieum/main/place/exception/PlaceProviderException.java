package shinhan.fibri.ieum.main.place.exception;

import org.springframework.http.HttpStatus;

public class PlaceProviderException extends RuntimeException {

	public PlaceProviderException(String message) {
		super(message);
	}

	public HttpStatus status() {
		return HttpStatus.BAD_GATEWAY;
	}

	public String code() {
		return "PLACE_PROVIDER_ERROR";
	}
}
