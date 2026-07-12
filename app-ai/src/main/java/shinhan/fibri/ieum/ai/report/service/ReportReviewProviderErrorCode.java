package shinhan.fibri.ieum.ai.report.service;

public enum ReportReviewProviderErrorCode {
	timeout,
	rate_limited,
	server_error,
	safety_refusal,
	invalid_output,
	transport_error
}
