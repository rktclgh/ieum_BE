package shinhan.fibri.ieum.main.place.exception;

public class PlaceRateLimitedException extends RuntimeException {

	public PlaceRateLimitedException() {
		super("Place request rate limit exceeded");
	}
}
