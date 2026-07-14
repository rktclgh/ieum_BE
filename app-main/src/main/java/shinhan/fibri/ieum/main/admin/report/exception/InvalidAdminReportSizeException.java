package shinhan.fibri.ieum.main.admin.report.exception;

public class InvalidAdminReportSizeException extends RuntimeException {

	public InvalidAdminReportSizeException() {
		super("Admin report page size must be between 1 and 50");
	}
}
