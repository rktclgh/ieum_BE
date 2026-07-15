package shinhan.fibri.ieum.main.notification.push;

public class WebPushDisabledException extends RuntimeException {

	public WebPushDisabledException() {
		super("Web Push is disabled");
	}
}
