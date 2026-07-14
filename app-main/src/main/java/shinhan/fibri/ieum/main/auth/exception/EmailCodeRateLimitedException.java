package shinhan.fibri.ieum.main.auth.exception;

public class EmailCodeRateLimitedException extends RuntimeException {

	public EmailCodeRateLimitedException() {
		super("Email verification code request rate limit exceeded");
	}
}
