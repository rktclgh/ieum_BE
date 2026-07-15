package shinhan.fibri.ieum.main.report.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ReportAiReviewResult(
	String decision,
	String recommendation,
	BigDecimal confidence,
	String reason,
	String modelVersion,
	String policyVersion,
	String policySetHash,
	OffsetDateTime reviewedAt,
	String reviewResultJson
) {

	public ReportAiReviewResult {
		requireText(decision, "decision", 20);
		requireText(recommendation, "recommendation", 40);
		if (confidence == null || confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("confidence must be between 0 and 1");
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		requireText(modelVersion, "modelVersion", 120);
		requireText(policyVersion, "policyVersion", 80);
		if (policySetHash == null || !policySetHash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("policySetHash must be 64 lowercase hex characters");
		}
		if (reviewedAt == null) {
			throw new IllegalArgumentException("reviewedAt must not be null");
		}
		if (reviewResultJson == null || reviewResultJson.isBlank()) {
			throw new IllegalArgumentException("reviewResultJson must not be blank");
		}
	}

	private static void requireText(String value, String name, int maxLength) {
		if (value == null || value.isBlank() || value.length() > maxLength) {
			throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
		}
	}
}
