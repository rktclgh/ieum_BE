package shinhan.fibri.ieum.main.admin.report.exception;

public class AdminReportConcurrentChangeException extends RuntimeException {

	public AdminReportConcurrentChangeException() {
		super("Admin report changed while acquiring ordered locks");
	}
}
