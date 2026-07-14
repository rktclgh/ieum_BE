package shinhan.fibri.ieum.main.admin.report.exception;

public class AdminReportAlreadyResolvedException extends RuntimeException {

	public AdminReportAlreadyResolvedException() {
		super("Admin report already has the opposite final decision");
	}
}
