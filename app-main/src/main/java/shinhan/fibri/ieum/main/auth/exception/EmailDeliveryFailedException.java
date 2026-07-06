package shinhan.fibri.ieum.main.auth.exception;

public class EmailDeliveryFailedException extends RuntimeException {

	public EmailDeliveryFailedException() {
		super("Failed to send email verification code");
	}

	public EmailDeliveryFailedException(Throwable cause) {
		super("Failed to send email verification code", cause);
	}
}
