package shinhan.fibri.ieum.main.auth.exception;

public class EmailNotVerifiedException extends RuntimeException {

	public EmailNotVerifiedException() {
		super("Email is not verified");
	}
}
