package shinhan.fibri.ieum.ai.report.service;

import java.util.Objects;

public class ReportReviewModelProviderException extends RuntimeException {

	private final ReportReviewProviderErrorCode errorCode;

	public ReportReviewModelProviderException(ReportReviewProviderErrorCode errorCode) {
		super("Report review model provider failed");
		this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
	}

	public ReportReviewProviderErrorCode errorCode() {
		return errorCode;
	}
}
