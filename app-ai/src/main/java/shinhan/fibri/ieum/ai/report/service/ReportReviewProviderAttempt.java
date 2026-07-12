package shinhan.fibri.ieum.ai.report.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Set;

public record ReportReviewProviderAttempt(
	String provider,
	String model,
	String outcome,
	ReportReviewProviderErrorCode errorCode,
	long latencyMs
) {
	private static final Set<String> OUTCOMES = Set.of("success", "failure");

	public ReportReviewProviderAttempt {
		if (isBlank(provider) || isBlank(model) || isBlank(outcome) || !OUTCOMES.contains(outcome) || latencyMs < 0) {
			throw new IllegalArgumentException("provider attempt must be valid");
		}
		if ("success".equals(outcome) && errorCode != null) {
			throw new IllegalArgumentException("successful provider attempts must not include an errorCode");
		}
		if ("failure".equals(outcome) && errorCode == null) {
			throw new IllegalArgumentException("failed provider attempts must include an errorCode");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@JsonIgnore
	public boolean isSuccess() {
		return "success".equals(outcome);
	}
}
