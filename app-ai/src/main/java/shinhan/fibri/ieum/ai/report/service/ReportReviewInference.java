package shinhan.fibri.ieum.ai.report.service;

import java.util.List;
public record ReportReviewInference(
	ReportPolicyEvaluationResult evaluation,
	String modelVersion,
	String promptVersion,
	boolean fallbackUsed,
	List<ReportReviewProviderAttempt> providerAttempts
) {

	public ReportReviewInference {
		if (evaluation == null || isBlank(modelVersion) || isBlank(promptVersion) || providerAttempts == null || providerAttempts.isEmpty()) {
			throw new IllegalArgumentException("report review inference must be valid");
		}
		if (providerAttempts.stream().anyMatch(attempt -> attempt == null)) {
			throw new IllegalArgumentException("providerAttempts must not contain null");
		}
		providerAttempts = List.copyOf(providerAttempts);
		if (providerAttempts.size() > 2 || providerAttempts.stream().filter(ReportReviewProviderAttempt::isSuccess).count() != 1
			|| !providerAttempts.getLast().isSuccess()) {
			throw new IllegalArgumentException("providerAttempts must end with one successful attempt");
		}
		if (fallbackUsed != (providerAttempts.size() == 2)) {
			throw new IllegalArgumentException("fallbackUsed must match the providerAttempts");
		}
		if (!modelVersion.equals(providerAttempts.getLast().model())) {
			throw new IllegalArgumentException("modelVersion must match the successful provider attempt");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
