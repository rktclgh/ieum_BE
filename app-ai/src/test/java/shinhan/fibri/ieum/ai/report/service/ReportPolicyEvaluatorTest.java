package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.report.domain.ReportEvidenceType;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportModelRuleMatch;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyDecision;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyRule;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySeverity;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;

class ReportPolicyEvaluatorTest {

	private final ReportPolicyEvaluator evaluator = new ReportPolicyEvaluator();

	@Test
	void qualifiedSuspendWinsOverHoldAndNormal() {
		ReportPolicyEvaluationResult result = evaluate(
			snapshot(
				rule("CONTENT-SUSPEND-001", ReportPolicyDecision.suspend, ReportPolicySeverity.high, "0.80", 1),
				rule("CONTENT-HOLD-001", ReportPolicyDecision.hold, ReportPolicySeverity.medium, "0.80", 100),
				rule("CONTENT-NORMAL-001", ReportPolicyDecision.normal, ReportPolicySeverity.low, "0.80", 100)
			),
			output(false,
				match("CONTENT-SUSPEND-001", "0.81"),
				match("CONTENT-HOLD-001", "0.99"),
				match("CONTENT-NORMAL-001", "0.99")
			)
		);

		assertThat(result.decision()).isEqualTo(ReportPolicyDecision.suspend);
		assertThat(result.severity()).isEqualTo(ReportPolicySeverity.high);
		assertThat(result.sourceRuleCode()).isEqualTo("CONTENT-SUSPEND-001");
		assertThat(result.evidenceMessageIds()).containsExactly(2L);
		assertThat(result.matchedRules()).extracting(ReportPolicyMatchedRule::ruleCode)
			.containsExactly("CONTENT-SUSPEND-001");
		assertThat(result.matchedRules()).extracting(ReportPolicyMatchedRule::revision).containsExactly(1);
	}

	@Test
	void qualifiedSuspendUsesSeverityThenPriorityToChooseTheExplanation() {
		ReportPolicyEvaluationResult result = evaluate(
			snapshot(
				rule("CONTENT-SUSPEND-LOW", ReportPolicyDecision.suspend, ReportPolicySeverity.high, "0.80", 100),
				rule("CONTENT-SUSPEND-HIGH", ReportPolicyDecision.suspend, ReportPolicySeverity.critical, "0.80", 1),
				rule("CONTENT-SUSPEND-PRIORITY", ReportPolicyDecision.suspend, ReportPolicySeverity.critical, "0.80", 10)
			),
			output(false,
				match("CONTENT-SUSPEND-LOW", "0.99"),
				match("CONTENT-SUSPEND-HIGH", "0.99"),
				match("CONTENT-SUSPEND-PRIORITY", "0.99")
			)
		);

		assertThat(result.severity()).isEqualTo(ReportPolicySeverity.critical);
		assertThat(result.sourceRuleCode()).isEqualTo("CONTENT-SUSPEND-PRIORITY");
	}

	@Test
	void sameSeverityAndPriorityUsesRuleCodeAscending() {
		ReportPolicyEvaluationResult result = evaluate(
			snapshot(
				rule("CONTENT-SUSPEND-Z", ReportPolicyDecision.suspend, ReportPolicySeverity.critical, "0.80", 10),
				rule("CONTENT-SUSPEND-A", ReportPolicyDecision.suspend, ReportPolicySeverity.critical, "0.80", 10)
			),
			output(false, match("CONTENT-SUSPEND-Z", "0.99"), match("CONTENT-SUSPEND-A", "0.99"))
		);

		assertThat(result.sourceRuleCode()).isEqualTo("CONTENT-SUSPEND-A");
	}

	@Test
	void qualifiedHoldReturnsTheRuleBackedHold() {
		ReportPolicyEvaluationResult result = evaluate(
			snapshot(rule("CONTENT-HOLD-001", ReportPolicyDecision.hold, ReportPolicySeverity.medium, "0.80", 1)),
			output(false, match("CONTENT-HOLD-001", "0.80"))
		);

		assertThat(result.decision()).isEqualTo(ReportPolicyDecision.hold);
		assertThat(result.category()).isEqualTo("content-hold-001");
		assertThat(result.severity()).isEqualTo(ReportPolicySeverity.medium);
	}

	@Test
	void subthresholdSuspendBecomesRuleBackedHold() {
		ReportPolicyEvaluationResult result = evaluate(
			snapshot(rule("CONTENT-SUSPEND-001", ReportPolicyDecision.suspend, ReportPolicySeverity.high, "0.90", 1)),
			output(false, match("CONTENT-SUSPEND-001", "0.89"))
		);

		assertThat(result.decision()).isEqualTo(ReportPolicyDecision.hold);
		assertThat(result.category()).isEqualTo("content-suspend-001");
		assertThat(result.severity()).isEqualTo(ReportPolicySeverity.high);
		assertThat(result.sourceRuleCode()).isEqualTo("CONTENT-SUSPEND-001");
	}

	@Test
	void uncertainWithoutAnyRuleMatchUsesTheSystemHoldContract() {
		ReportPolicyEvaluationResult result = evaluate(snapshot(), output(true));

		assertThat(result.decision()).isEqualTo(ReportPolicyDecision.hold);
		assertThat(result.category()).isEqualTo("model_uncertain");
		assertThat(result.severity()).isEqualTo(ReportPolicySeverity.low);
		assertThat(result.confidence()).isEqualByComparingTo("0.0000");
		assertThat(result.sourceRuleCode()).isNull();
		assertThat(result.reason()).isEqualTo("Model uncertainty requires manual review");
		assertThat(result.evidenceMessageIds()).isEmpty();
		assertThat(result.matchedRules()).isEmpty();
	}

	@Test
	void qualifyingNormalReturnsNormalAndNoMatchStaysNormal() {
		ReportPolicyEvaluationResult qualified = evaluate(
			snapshot(rule("CONTENT-NORMAL-001", ReportPolicyDecision.normal, ReportPolicySeverity.low, "0.80", 1)),
			output(false, match("CONTENT-NORMAL-001", "0.80"))
		);
		ReportPolicyEvaluationResult noMatch = evaluate(snapshot(), output(false));

		assertThat(qualified.decision()).isEqualTo(ReportPolicyDecision.normal);
		assertThat(qualified.severity()).isEqualTo(ReportPolicySeverity.low);
		assertThat(noMatch.decision()).isEqualTo(ReportPolicyDecision.normal);
		assertThat(noMatch.category()).isNull();
		assertThat(noMatch.severity()).isNull();
	}

	@Test
	void rejectsAProviderMatchForAnUnknownPolicyRule() {
		assertThatThrownBy(() -> evaluate(snapshot(), output(false, match("CONTENT-UNKNOWN-001", "0.99"))))
			.isInstanceOf(InvalidReportModelOutputException.class)
			.hasMessageContaining("Unknown policy rule");
	}

	@Test
	void subthresholdHoldStaysRuleBackedHoldAndNormalDoesNotEscalate() {
		ReportPolicyEvaluationResult subthresholdHold = evaluate(
			snapshot(rule("CONTENT-HOLD-001", ReportPolicyDecision.hold, ReportPolicySeverity.medium, "0.90", 1)),
			output(false, match("CONTENT-HOLD-001", "0.89"))
		);
		ReportPolicyEvaluationResult subthresholdNormal = evaluate(
			snapshot(rule("CONTENT-NORMAL-001", ReportPolicyDecision.normal, ReportPolicySeverity.low, "0.90", 1)),
			output(false, match("CONTENT-NORMAL-001", "0.89"))
		);

		assertThat(subthresholdHold.decision()).isEqualTo(ReportPolicyDecision.hold);
		assertThat(subthresholdHold.sourceRuleCode()).isEqualTo("CONTENT-HOLD-001");
		assertThat(subthresholdNormal.decision()).isEqualTo(ReportPolicyDecision.normal);
		assertThat(subthresholdNormal.sourceRuleCode()).isNull();
	}

	@Test
	void uncertainDoesNotOverrideQualifiedRuleBackedDecisions() {
		ReportPolicyEvaluationResult qualifiedHold = evaluate(
			snapshot(rule("CONTENT-HOLD-001", ReportPolicyDecision.hold, ReportPolicySeverity.medium, "0.80", 1)),
			output(true, match("CONTENT-HOLD-001", "0.80"))
		);

		assertThat(qualifiedHold.decision()).isEqualTo(ReportPolicyDecision.hold);
		assertThat(qualifiedHold.sourceRuleCode()).isEqualTo("CONTENT-HOLD-001");
		assertThat(qualifiedHold.category()).isEqualTo("content-hold-001");
	}

	private ReportPolicySnapshot snapshot(ReportPolicyRule... rules) {
		return new ReportPolicySnapshot("a".repeat(64), Arrays.asList(rules));
	}

	private ReportModelReviewOutput output(boolean uncertain, ReportModelRuleMatch... matches) {
		return new ReportModelReviewOutput(Arrays.asList(matches), uncertain);
	}

	private ReportModelRuleMatch match(String ruleCode, String confidence) {
		return new ReportModelRuleMatch(ruleCode, new BigDecimal(confidence), List.of(2L), ruleCode + " reason");
	}

	private ReportPolicyEvaluationResult evaluate(ReportPolicySnapshot snapshot, ReportModelReviewOutput output) {
		return evaluator.evaluate(snapshot, output, context());
	}

	private List<ReportReviewEvidenceMessage> context() {
		return List.of(new ReportReviewEvidenceMessage(2L, "reported_user", "content", true));
	}

	private ReportPolicyRule rule(
		String ruleCode,
		ReportPolicyDecision decision,
		ReportPolicySeverity severity,
		String minConfidence,
		int priority
	) {
		return new ReportPolicyRule(
			ruleCode,
			ruleCode + " title",
			ruleCode.toLowerCase(),
			"Policy criteria",
			decision,
			severity,
			new BigDecimal(minConfidence),
			ReportEvidenceType.text,
			priority,
			1,
			List.of(),
			List.of()
		);
	}
}
