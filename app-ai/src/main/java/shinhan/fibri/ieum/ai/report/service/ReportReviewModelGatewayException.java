package shinhan.fibri.ieum.ai.report.service;

public class ReportReviewModelGatewayException extends RuntimeException {

	public ReportReviewModelGatewayException() {
		super("Report review model inference failed");
	}

	public ReportReviewModelGatewayException(Throwable cause) {
		super("Report review model inference failed", cause);
	}
}
