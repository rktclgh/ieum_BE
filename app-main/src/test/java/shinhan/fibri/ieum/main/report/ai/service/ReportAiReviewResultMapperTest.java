package shinhan.fibri.ieum.main.report.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

class ReportAiReviewResultMapperTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ReportAiReviewResultMapper mapper = new ReportAiReviewResultMapper(objectMapper);

	@Test
	void mapsCriticalSuspendToASevenDayAutomaticSanctionAndFullResultJson() throws Exception {
		OffsetDateTime reviewedAt = OffsetDateTime.parse("2026-07-14T12:00:00+09:00");

		MappedReportAiReview mapped = mapper.map(response("suspend", "critical"), reviewedAt);

		assertThat(mapped.automaticSanctionDuration()).isEqualTo(Duration.ofDays(7));
		assertThat(mapped.result().decision()).isEqualTo("suspend");
		assertThat(mapped.result().recommendation()).isEqualTo("temporary_suspend");
		assertThat(mapped.result().confidence()).isEqualByComparingTo("0.9800");
		assertThat(mapped.result().policyVersion()).isEqualTo("report-review-v1");
		assertThat(mapped.result().reviewedAt()).isEqualTo(reviewedAt);
		var json = objectMapper.readTree(mapped.result().reviewResultJson());
		assertThat(json.path("severity").asText()).isEqualTo("critical");
		assertThat(json.path("providerAttempts").get(0).path("provider").asText()).isEqualTo("bedrock");
	}

	@Test
	void mapsHighSuspendToASeventyTwoHourAutomaticSanction() {
		MappedReportAiReview mapped = mapper.map(
			response("suspend", "high"),
			OffsetDateTime.parse("2026-07-14T12:00:00+09:00")
		);

		assertThat(mapped.automaticSanctionDuration()).isEqualTo(Duration.ofHours(72));
	}

	@Test
	void mapsTheSelectedPolicyRulesSevenDayAutomaticSanctionDuration() {
		MappedReportAiReview mapped = mapper.map(
			response("suspend", "high", 7),
			OffsetDateTime.parse("2026-07-15T10:00:00+09:00")
		);

		assertThat(mapped.automaticSanctionDuration()).isEqualTo(Duration.ofDays(7));
	}

	@Test
	void mapsTheSelectedPolicyRulesThirtyDayAutomaticSanctionDuration() {
		MappedReportAiReview mapped = mapper.map(
			response("suspend", "high", 30),
			OffsetDateTime.parse("2026-07-15T10:00:00+09:00")
		);

		assertThat(mapped.automaticSanctionDuration()).isEqualTo(Duration.ofDays(30));
	}

	@Test
	void rejectsAnInvalidRuleSpecificAutomaticSanctionDuration() {
		assertThatThrownBy(() -> mapper.map(
			response("suspend", "high", 0),
			OffsetDateTime.parse("2026-07-15T10:00:00+09:00")
		))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");
	}

	@Test
	void acceptsNoMatchNormalWithoutSeverityAndCreatesNoSanction() {
		ReportReviewResponse response = response("normal", null);

		MappedReportAiReview mapped = mapper.map(response, OffsetDateTime.now());

		assertThat(mapped.automaticSanctionDuration()).isNull();
		assertThat(mapped.result().recommendation()).isEqualTo("dismiss");
	}

	@Test
	void rejectsSuspendWithoutHighOrCriticalSeverity() {
		assertThatThrownBy(() -> mapper.map(response("suspend", "medium"), OffsetDateTime.now()))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");
	}

	@Test
	void rejectsSuspendWithoutPolicyEvidenceOrMatchedRule() {
		ReportReviewResponse valid = response("suspend", "critical");

		assertThatThrownBy(() -> mapper.map(copy(
			valid,
			objectMapper.createArrayNode(),
			valid.matchedRules(),
			valid.policySnapshot(),
			valid.confidence()
		), OffsetDateTime.now()))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");

		assertThatThrownBy(() -> mapper.map(copy(
			valid,
			valid.evidence(),
			objectMapper.createArrayNode(),
			valid.policySnapshot(),
			valid.confidence()
		), OffsetDateTime.now()))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");
	}

	@Test
	void rejectsSuspendWhenMatchedRuleDoesNotMatchThePolicySnapshot() {
		ReportReviewResponse valid = response("suspend", "critical");
		var mismatchedSnapshot = valid.policySnapshot().deepCopy();
		((com.fasterxml.jackson.databind.node.ObjectNode)mismatchedSnapshot.path("rules").get(0))
			.put("revision", 2);

		assertThatThrownBy(() -> mapper.map(copy(
			valid,
			valid.evidence(),
			valid.matchedRules(),
			mismatchedSnapshot,
			valid.confidence()
		), OffsetDateTime.now()))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");
	}

	@Test
	void rejectsSuspendBelowTheMatchedPolicyConfidenceThreshold() {
		ReportReviewResponse valid = response("suspend", "critical");

		assertThatThrownBy(() -> mapper.map(copy(
			valid,
			valid.evidence(),
			valid.matchedRules(),
			valid.policySnapshot(),
			new BigDecimal("0.96")
		), OffsetDateTime.now()))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");
	}

	@Test
	void rejectsIncompleteProvenance() {
		ReportReviewResponse invalid = new ReportReviewResponse(
			"hold", "abuse", "medium", new BigDecimal("0.5"), "manual review",
			objectMapper.createArrayNode(), objectMapper.createArrayNode(), "a".repeat(64),
			objectMapper.createObjectNode(), "model-v1", "report-review-v1", false, null
		);

		assertThatThrownBy(() -> mapper.map(invalid, OffsetDateTime.now()))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");
	}

	@Test
	void rejectsNullDecisionAsAnInvalidAiResponse() {
		assertThatThrownBy(() -> mapper.map(response(null, "medium"), OffsetDateTime.now()))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");
	}

	@Test
	void rejectsNullEvidenceTypeAsAnInvalidAiResponse() {
		ReportReviewResponse valid = response("suspend", "critical");
		var evidence = valid.evidence().deepCopy();
		((com.fasterxml.jackson.databind.node.ObjectNode)evidence.get(0)).putNull("type");

		assertThatThrownBy(() -> mapper.map(copy(
			valid,
			evidence,
			valid.matchedRules(),
			valid.policySnapshot(),
			valid.confidence()
		), OffsetDateTime.now()))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_AI_RESPONSE_INVALID");
	}

	private ReportReviewResponse response(String decision, String severity) {
		return response(decision, severity, null);
	}

	private ReportReviewResponse response(String decision, String severity, Integer automaticSanctionDays) {
		var providerAttempts = objectMapper.createArrayNode();
		providerAttempts.addObject()
			.put("provider", "bedrock")
			.put("model", "model-v1")
			.put("outcome", "success")
			.putNull("errorCode")
			.put("latencyMs", 100);
		var evidence = objectMapper.createArrayNode();
		var matchedRules = objectMapper.createArrayNode();
		var policySnapshot = objectMapper.createObjectNode();
		policySnapshot.put("policySetHash", "a".repeat(64));
		var rules = policySnapshot.putArray("rules");
		if ("suspend".equals(decision)) {
			evidence.addObject().put("messageId", 2L).put("type", "text");
			matchedRules.addObject().put("ruleCode", "VIOLENCE-THREAT-001").put("revision", 1);
			rules.addObject()
				.put("ruleCode", "VIOLENCE-THREAT-001")
				.put("category", "abuse")
				.put("decision", "suspend")
				.put("severity", severity)
				.put("minConfidence", 0.97)
				.put("revision", 1);
			if (automaticSanctionDays != null) {
				((com.fasterxml.jackson.databind.node.ObjectNode) rules.get(0))
					.put("automaticSanctionDays", automaticSanctionDays);
			}
		}
		return new ReportReviewResponse(
			decision,
			severity == null ? null : "abuse",
			severity,
			new BigDecimal("suspend".equals(decision) ? "0.98001" : "0.94001"),
			"abusive content detected",
			evidence,
			matchedRules,
			"a".repeat(64),
			policySnapshot,
			"model-v1",
			"report-review-v1",
			false,
			providerAttempts
		);
	}

	private ReportReviewResponse copy(
		ReportReviewResponse source,
		JsonNode evidence,
		JsonNode matchedRules,
		JsonNode policySnapshot,
		BigDecimal confidence
	) {
		return new ReportReviewResponse(
			source.decision(), source.category(), source.severity(), confidence, source.reason(),
			evidence, matchedRules, source.policySetHash(), policySnapshot,
			source.modelVersion(), source.promptVersion(), source.fallbackUsed(), source.providerAttempts()
		);
	}
}
