package shinhan.fibri.ieum.main.admin.user.exception;

public class HardDeleteConfirmationMismatchException extends RuntimeException {

	public HardDeleteConfirmationMismatchException() {
		super("confirmationEmail does not match target user email");
	}

	public String field() {
		return "confirmationEmail";
	}
}
