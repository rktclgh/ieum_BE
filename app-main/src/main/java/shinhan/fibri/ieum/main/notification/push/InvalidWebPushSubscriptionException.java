package shinhan.fibri.ieum.main.notification.push;

public class InvalidWebPushSubscriptionException extends RuntimeException {

	private final String field;

	public InvalidWebPushSubscriptionException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return field;
	}
}
