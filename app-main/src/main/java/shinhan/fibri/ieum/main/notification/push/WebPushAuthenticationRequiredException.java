package shinhan.fibri.ieum.main.notification.push;

public class WebPushAuthenticationRequiredException extends RuntimeException {

	public WebPushAuthenticationRequiredException() {
		super("Authentication is required");
	}
}
