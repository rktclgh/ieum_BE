package shinhan.fibri.ieum.main.admin.user.exception;

public class CannotPromoteAdminException extends RuntimeException {

	public CannotPromoteAdminException() {
		super("Only active normal users can be promoted");
	}
}
