package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportModelRuleMatch;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;

class FallbackReportReviewModelGatewayTest {

	@Test
	void keepsAValidPrimaryDecisionWithoutCallingGeminiFallback() {
		StubProvider primary = new StubProvider("bedrock", "amazon.nova-lite-v1:0", new ReportModelReviewOutput(List.of(), true));
		StubProvider fallback = new StubProvider("gemini", "gemini-3.1-flash-lite", new ReportModelReviewOutput(List.of(), false));
		FallbackReportReviewModelGateway gateway = new FallbackReportReviewModelGateway(primary, fallback, "report-review-v1");

		ReportReviewInference inference = gateway.review(prepared(), emptySnapshot(), output -> evaluation(output));

		assertThat(inference.fallbackUsed()).isFalse();
		assertThat(inference.modelVersion()).isEqualTo("amazon.nova-lite-v1:0");
		assertThat(inference.providerAttempts()).hasSize(1);
		assertThat(inference.providerAttempts().getFirst().outcome()).isEqualTo("success");
		assertThat(fallback.calls).isZero();
	}

	@Test
	void fallsBackWhenPrimaryOutputViolatesThePolicyContract() {
		StubProvider primary = new StubProvider("bedrock", "amazon.nova-lite-v1:0", new ReportModelReviewOutput(List.of(
			new ReportModelRuleMatch("UNKNOWN-RULE", new java.math.BigDecimal("0.99"), List.of(2L), "invalid")
		), false));
		StubProvider fallback = new StubProvider("gemini", "gemini-3.1-flash-lite", new ReportModelReviewOutput(List.of(), false));
		FallbackReportReviewModelGateway gateway = new FallbackReportReviewModelGateway(primary, fallback, "report-review-v1");

		ReportReviewInference inference = gateway.review(prepared(), emptySnapshot(), this::evaluation);

		assertThat(inference.fallbackUsed()).isTrue();
		assertThat(inference.modelVersion()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(inference.providerAttempts())
			.extracting(ReportReviewProviderAttempt::errorCode)
			.containsExactly(ReportReviewProviderErrorCode.invalid_output, null);
		assertThat(fallback.calls).isEqualTo(1);
	}

	@Test
	void fallsBackWhenThePrimaryProviderFails() {
		StubProvider primary = new StubProvider("bedrock", "amazon.nova-lite-v1:0", ReportReviewProviderErrorCode.timeout);
		StubProvider fallback = new StubProvider("gemini", "gemini-3.1-flash-lite", new ReportModelReviewOutput(List.of(), false));
		FallbackReportReviewModelGateway gateway = new FallbackReportReviewModelGateway(primary, fallback, "report-review-v1");

		ReportReviewInference inference = gateway.review(prepared(), emptySnapshot(), this::evaluation);

		assertThat(inference.fallbackUsed()).isTrue();
		assertThat(inference.modelVersion()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(inference.providerAttempts())
			.extracting(ReportReviewProviderAttempt::errorCode)
			.containsExactly(ReportReviewProviderErrorCode.timeout, null);
		assertThat(fallback.calls).isEqualTo(1);
	}

	@Test
	void failsForRetryInsteadOfInventingAHoldWhenBothProvidersFail() {
		StubProvider primary = new StubProvider("bedrock", "amazon.nova-lite-v1:0", ReportReviewProviderErrorCode.timeout);
		StubProvider fallback = new StubProvider("gemini", "gemini-3.1-flash-lite", ReportReviewProviderErrorCode.server_error);
		FallbackReportReviewModelGateway gateway = new FallbackReportReviewModelGateway(primary, fallback, "report-review-v1");

		assertThatThrownBy(() -> gateway.review(prepared(), emptySnapshot(), this::evaluation))
			.isInstanceOfSatisfying(ReportReviewModelGatewayException.class, exception ->
				assertThat(exception.providerAttempts())
					.extracting(
						ReportReviewProviderAttempt::provider,
						ReportReviewProviderAttempt::model,
						ReportReviewProviderAttempt::outcome,
						ReportReviewProviderAttempt::errorCode
					)
					.containsExactly(
						org.assertj.core.groups.Tuple.tuple(
							"bedrock",
							"amazon.nova-lite-v1:0",
							"failure",
							ReportReviewProviderErrorCode.timeout
						),
						org.assertj.core.groups.Tuple.tuple(
							"gemini",
							"gemini-3.1-flash-lite",
							"failure",
							ReportReviewProviderErrorCode.server_error
						)
					)
			);

		assertThat(primary.calls).isEqualTo(1);
		assertThat(fallback.calls).isEqualTo(1);
	}

	private ReportPolicyEvaluationResult evaluation(ReportModelReviewOutput output) {
		return new ReportPolicyEvaluator().evaluate(emptySnapshot(), output, prepared().evidenceMessages());
	}

	private ReportPolicySnapshot emptySnapshot() {
		return new ReportPolicySnapshot("a".repeat(64), List.of());
	}

	private PreparedReportReview prepared() {
		return new PreparedReportReview(
			900L,
			UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
			2L,
			"harassment",
			"detail",
			"b".repeat(64),
			List.of(new ReportReviewEvidenceMessage(2L, "reported_user", "abuse", false)),
			new ReportEvidenceImageBatch(Map.of(), 0L)
		);
	}

	private static final class StubProvider implements ReportReviewModelProvider {

		private final String provider;
		private final String model;
		private final ReportModelReviewOutput output;
		private final ReportReviewProviderErrorCode errorCode;
		private int calls;

		private StubProvider(String provider, String model, ReportModelReviewOutput output) {
			this.provider = provider;
			this.model = model;
			this.output = output;
			this.errorCode = null;
		}

		private StubProvider(String provider, String model, ReportReviewProviderErrorCode errorCode) {
			this.provider = provider;
			this.model = model;
			this.output = null;
			this.errorCode = errorCode;
		}

		@Override
		public String provider() {
			return provider;
		}

		@Override
		public String model() {
			return model;
		}

		@Override
		public ReportModelReviewOutput review(PreparedReportReview preparedReview, ReportPolicySnapshot policySnapshot) {
			calls++;
			if (errorCode != null) {
				throw new ReportReviewModelProviderException(errorCode);
			}
			return output;
		}
	}
}
