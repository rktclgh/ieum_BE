package shinhan.fibri.ieum.main.admin.content.exception;

public class HardDeleteConfirmationMismatchException extends RuntimeException {

	public HardDeleteConfirmationMismatchException() {
		super("confirmationToken does not match target content");
	}

	public String field() {
		return "confirmationToken";
	}
}
