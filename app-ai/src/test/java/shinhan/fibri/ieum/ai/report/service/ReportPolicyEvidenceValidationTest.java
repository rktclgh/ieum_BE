package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

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

class ReportPolicyEvidenceValidationTest {

	private final ReportPolicyEvaluator evaluator = new ReportPolicyEvaluator();

	@Test
	void rejectsEvidenceMessageIdsThatAreNotInTheReviewContext() {
		assertThatThrownBy(() -> evaluator.evaluate(
			snapshot(rule(ReportEvidenceType.text)),
			output(match("CONTENT-SUSPEND-001", List.of(3L))),
			List.of(message(2L, "reported_user", "text", false))
		))
			.isInstanceOf(InvalidReportModelOutputException.class)
			.hasMessageContaining("evidence message");
	}

	@Test
	void rejectsTextAndImageModalityMismatches() {
		assertThatThrownBy(() -> evaluator.evaluate(
			snapshot(rule(ReportEvidenceType.text)),
			output(match("CONTENT-SUSPEND-001", List.of(2L))),
			List.of(message(2L, "reported_user", " ", true))
		))
			.isInstanceOf(InvalidReportModelOutputException.class)
			.hasMessageContaining("text evidence");

		assertThatThrownBy(() -> evaluator.evaluate(
			snapshot(rule(ReportEvidenceType.image)),
			output(match("CONTENT-SUSPEND-001", List.of(2L))),
			List.of(message(2L, "reported_user", "text", false))
		))
			.isInstanceOf(InvalidReportModelOutputException.class)
			.hasMessageContaining("image evidence");
	}

	@Test
	void rejectsBothEvidenceWithoutTextAndImageAcrossTheCitedSet() {
		assertThatThrownBy(() -> evaluator.evaluate(
			snapshot(rule(ReportEvidenceType.both)),
			output(match("CONTENT-SUSPEND-001", List.of(2L, 3L))),
			List.of(
				message(2L, "reported_user", "text", false),
				message(3L, "other_actor_1", "context", false)
			)
		))
			.isInstanceOf(InvalidReportModelOutputException.class)
			.hasMessageContaining("both evidence");
	}

	@Test
	void acceptsBothEvidenceWhenTheCitedSetContainsVerifiedTextAndImage() {
		ReportPolicyEvaluationResult result = evaluator.evaluate(
			snapshot(rule(ReportEvidenceType.both)),
			output(match("CONTENT-SUSPEND-001", List.of(2L, 3L))),
			List.of(
				message(2L, "reported_user", "text", true),
				message(3L, "other_actor_1", "context", false)
			)
		);

		assertThat(result.decision()).isEqualTo(ReportPolicyDecision.suspend);
	}

	@Test
	void rejectsSuspendWhoseEvidenceIsOnlyFromAnotherActor() {
		assertThatThrownBy(() -> evaluator.evaluate(
			snapshot(rule(ReportEvidenceType.text)),
			output(match("CONTENT-SUSPEND-001", List.of(2L))),
			List.of(message(2L, "other_actor_1", "text", true))
		))
			.isInstanceOf(InvalidReportModelOutputException.class)
			.hasMessageContaining("reported_user");
	}

	@Test
	void rejectsSuspendWhenReportedUserSubsetDoesNotSatisfyBothEvidence() {
		assertThatThrownBy(() -> evaluator.evaluate(
			snapshot(rule(ReportEvidenceType.both)),
			output(match("CONTENT-SUSPEND-001", List.of(2L, 3L))),
			List.of(
				message(2L, "reported_user", "text", false),
				message(3L, "other_actor_1", "context", true)
			)
		))
			.isInstanceOf(InvalidReportModelOutputException.class)
			.hasMessageContaining("reported_user");
	}

	private ReportPolicySnapshot snapshot(ReportPolicyRule rule) {
		return new ReportPolicySnapshot("a".repeat(64), List.of(rule));
	}

	private ReportModelReviewOutput output(ReportModelRuleMatch match) {
		return new ReportModelReviewOutput(List.of(match), false);
	}

	private ReportModelRuleMatch match(String ruleCode, List<Long> evidenceMessageIds) {
		return new ReportModelRuleMatch(ruleCode, new BigDecimal("0.99"), evidenceMessageIds, "reason");
	}

	private ReportReviewEvidenceMessage message(long messageId, String actor, String content, boolean verifiedImage) {
		return new ReportReviewEvidenceMessage(messageId, actor, content, verifiedImage);
	}

	private ReportPolicyRule rule(ReportEvidenceType evidenceType) {
		return new ReportPolicyRule(
			"CONTENT-SUSPEND-001",
			"Suspend policy",
			"abuse",
			"Abusive content criteria",
			ReportPolicyDecision.suspend,
			ReportPolicySeverity.high,
			new BigDecimal("0.80"),
			evidenceType,
			1,
			1,
			List.of(),
			List.of()
		);
	}
}
