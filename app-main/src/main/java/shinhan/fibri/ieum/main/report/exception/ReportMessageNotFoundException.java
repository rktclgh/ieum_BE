package shinhan.fibri.ieum.main.report.exception;

public class ReportMessageNotFoundException extends RuntimeException {

	public ReportMessageNotFoundException() {
		super("Report target message not found");
	}
}
