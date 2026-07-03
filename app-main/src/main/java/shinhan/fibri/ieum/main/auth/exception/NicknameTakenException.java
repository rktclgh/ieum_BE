package shinhan.fibri.ieum.main.auth.exception;

public class NicknameTakenException extends RuntimeException {

	public NicknameTakenException() {
		super("Nickname is already taken");
	}
}
