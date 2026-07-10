package shinhan.fibri.ieum.main.notification.sse;

public class SseAuthenticationRequiredException extends RuntimeException {

	public SseAuthenticationRequiredException() {
		super("Authentication is required");
	}
}
