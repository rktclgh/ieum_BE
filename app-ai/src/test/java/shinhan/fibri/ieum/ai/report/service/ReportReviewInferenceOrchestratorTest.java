package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.report.domain.ReportEvidenceType;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportModelRuleMatch;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyDecision;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyRule;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySeverity;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

class ReportReviewInferenceOrchestratorTest {

	@Test
	void evaluatesProviderOutputAndMapsDecisionWithAuditProvenance() {
		ReportPolicySnapshot snapshot = new ReportPolicySnapshot("a".repeat(64), List.of(
			new ReportPolicyRule(
				"CONTENT-ABUSE-001",
				"Abuse policy",
				"abuse",
				"Abusive content criteria",
				ReportPolicyDecision.hold,
				ReportPolicySeverity.medium,
				new BigDecimal("0.8000"),
				ReportEvidenceType.text,
				10,
				3,
				List.of(),
				List.of()
			)
		));
		PreparedReportReview prepared = new PreparedReportReview(
			900L,
			UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
			2L,
			"harassment",
			"detail",
			"b".repeat(64),
			List.of(new ReportReviewEvidenceMessage(2L, "reported_user", "abuse", false)),
			new ReportEvidenceImageBatch(Map.of(), 0L)
		);
		ReportReviewModelGateway gateway = (review, policySnapshot, outputValidator) -> new ReportReviewInference(
			outputValidator.evaluate(new ReportModelReviewOutput(List.of(
				new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("0.9900"), List.of(2L), "abuse found")
			), false)),
			"amazon.nova-lite-v1:0",
			"report-review-v1",
			false,
			List.of(new ReportReviewProviderAttempt("bedrock", "amazon.nova-lite-v1:0", "success", null, 820L))
		);
		ReportReviewInferenceOrchestrator orchestrator = new ReportReviewInferenceOrchestrator(
			() -> snapshot,
			gateway,
			new ReportPolicyEvaluator(),
			new ObjectMapper().findAndRegisterModules()
		);

		ReportReviewResponse response = orchestrator.review(prepared);

		assertThat(response.decision()).isEqualTo("hold");
		assertThat(response.category()).isEqualTo("abuse");
		assertThat(response.severity()).isEqualTo("medium");
		assertThat(response.confidence()).isEqualByComparingTo("0.9900");
		assertThat(response.reason()).isEqualTo("abuse found");
		assertThat(response.evidence().get(0).path("messageId").asLong()).isEqualTo(2L);
		assertThat(response.evidence().get(0).path("type").asText()).isEqualTo("text");
		assertThat(response.matchedRules().get(0).path("ruleCode").asText()).isEqualTo("CONTENT-ABUSE-001");
		assertThat(response.matchedRules().get(0).path("revision").asInt()).isEqualTo(3);
		assertThat(response.policySetHash()).isEqualTo("a".repeat(64));
		assertThat(response.policySnapshot().path("rules").get(0).path("ruleCode").asText()).isEqualTo("CONTENT-ABUSE-001");
		assertThat(response.modelVersion()).isEqualTo("amazon.nova-lite-v1:0");
		assertThat(response.promptVersion()).isEqualTo("report-review-v1");
		assertThat(response.fallbackUsed()).isFalse();
		assertThat(response.providerAttempts().get(0).path("provider").asText()).isEqualTo("bedrock");
		List<String> providerAttemptFields = new ArrayList<>();
		response.providerAttempts().get(0).fieldNames().forEachRemaining(providerAttemptFields::add);
		assertThat(providerAttemptFields).containsExactly("provider", "model", "outcome", "errorCode", "latencyMs");
	}
}
