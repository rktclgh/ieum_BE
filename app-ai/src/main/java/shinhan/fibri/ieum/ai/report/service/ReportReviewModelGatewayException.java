package shinhan.fibri.ieum.ai.report.service;

import java.util.List;

public class ReportReviewModelGatewayException extends RuntimeException {

	private final List<ReportReviewProviderAttempt> providerAttempts;

	public ReportReviewModelGatewayException() {
		this(List.of());
	}

	public ReportReviewModelGatewayException(List<ReportReviewProviderAttempt> providerAttempts) {
		super("Report review model inference failed");
		this.providerAttempts = providerAttempts == null ? List.of() : List.copyOf(providerAttempts);
	}

	public ReportReviewModelGatewayException(Throwable cause) {
		super("Report review model inference failed", cause);
		this.providerAttempts = List.of();
	}

	public List<ReportReviewProviderAttempt> providerAttempts() {
		return providerAttempts;
	}
}
