package shinhan.fibri.ieum.main.notification.exception;

public class NotificationNotFoundException extends RuntimeException {

	public NotificationNotFoundException() {
		super("Notification not found");
	}
}
