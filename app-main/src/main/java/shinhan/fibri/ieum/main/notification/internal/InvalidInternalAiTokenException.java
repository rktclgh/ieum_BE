package shinhan.fibri.ieum.main.notification.internal;

public class InvalidInternalAiTokenException extends RuntimeException {

	public InvalidInternalAiTokenException() {
		super("Internal AI authentication failed");
	}
}
