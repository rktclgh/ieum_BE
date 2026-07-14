package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyDecision;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySeverity;

class ReportReviewInferenceTest {

	@Test
	void rejectsProviderProvenanceWithoutOneFinalSuccessfulAttempt() {
		assertThatThrownBy(() -> inference(
			"amazon.nova-lite-v1:0",
			false,
			List.of(new ReportReviewProviderAttempt(
				"bedrock", "amazon.nova-lite-v1:0", "failure", ReportReviewProviderErrorCode.timeout, 100L
			))
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("providerAttempts");
	}

	@Test
	void rejectsFallbackFlagsAndModelVersionsThatDisagreeWithAttempts() {
		assertThatThrownBy(() -> inference(
			"amazon.nova-lite-v1:0",
			true,
			List.of(new ReportReviewProviderAttempt(
				"bedrock", "amazon.nova-lite-v1:0", "success", null, 100L
			))
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fallbackUsed");

		assertThatThrownBy(() -> inference(
			"gemini-3.1-flash-lite",
			false,
			List.of(new ReportReviewProviderAttempt(
				"bedrock", "amazon.nova-lite-v1:0", "success", null, 100L
			))
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("modelVersion");
	}

	@Test
	void rejectsNullProviderFieldsAndAttemptsWithDomainValidationErrors() {
		assertThatThrownBy(() -> new ReportReviewProviderAttempt(
			"bedrock", "amazon.nova-lite-v1:0", null, null, 100L
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("provider attempt");

		assertThatThrownBy(() -> inference(
			"amazon.nova-lite-v1:0",
			false,
			Arrays.asList((ReportReviewProviderAttempt) null)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("providerAttempts");
	}

	private ReportReviewInference inference(
		String modelVersion,
		boolean fallbackUsed,
		List<ReportReviewProviderAttempt> attempts
	) {
		return new ReportReviewInference(
			new ReportPolicyEvaluationResult(
				ReportPolicyDecision.normal,
				null,
				null,
				BigDecimal.ZERO,
				"No policy rule matched",
				null,
				List.of(),
				List.of()
			),
			modelVersion,
			"report-review-v1",
			fallbackUsed,
			attempts
		);
	}
}
