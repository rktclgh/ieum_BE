package shinhan.fibri.ieum.main.auth.exception;

public class SocialAlreadyRegisteredException extends RuntimeException {

	public SocialAlreadyRegisteredException() {
		super("Social account is already registered");
	}
}
