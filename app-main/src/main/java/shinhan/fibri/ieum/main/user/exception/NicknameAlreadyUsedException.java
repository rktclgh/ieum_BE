package shinhan.fibri.ieum.main.user.exception;

public class NicknameAlreadyUsedException extends RuntimeException {

	public NicknameAlreadyUsedException() {
		super("Nickname is already used");
	}
}
