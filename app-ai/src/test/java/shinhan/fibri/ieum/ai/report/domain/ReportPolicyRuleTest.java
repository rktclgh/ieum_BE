package shinhan.fibri.ieum.ai.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportPolicyRuleTest {

	@Test
	void acceptsAHighSeveritySuspendRule() {
		assertThatCode(() -> rule(ReportPolicyDecision.suspend, ReportPolicySeverity.high)).doesNotThrowAnyException();
	}

	@Test
	void rejectsSuspendRuleBelowHighSeverity() {
		assertThatThrownBy(() -> rule(ReportPolicyDecision.suspend, ReportPolicySeverity.medium))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("suspend");
	}

	@Test
	void rejectsNormalRuleAboveLowSeverity() {
		assertThatThrownBy(() -> rule(ReportPolicyDecision.normal, ReportPolicySeverity.medium))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("normal");
	}

	@Test
	void acceptsOnlyValidSuspendAutomaticSanctionDurations() {
		assertThat(rule(ReportPolicyDecision.suspend, ReportPolicySeverity.high, 7).automaticSanctionDays()).isEqualTo(7);
		assertThatThrownBy(() -> rule(ReportPolicyDecision.hold, ReportPolicySeverity.medium, 7))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("automaticSanctionDays");
		assertThatThrownBy(() -> rule(ReportPolicyDecision.suspend, ReportPolicySeverity.high, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("automaticSanctionDays");
	}

	private ReportPolicyRule rule(ReportPolicyDecision decision, ReportPolicySeverity severity) {
		return rule(decision, severity, null);
	}

	private ReportPolicyRule rule(ReportPolicyDecision decision, ReportPolicySeverity severity, Integer automaticSanctionDays) {
		return new ReportPolicyRule(
			"CONTENT-ABUSE-001",
			"Abuse policy",
			"abuse",
			"Abusive content criteria",
			decision,
			severity,
			automaticSanctionDays,
			new BigDecimal("0.8500"),
			ReportEvidenceType.text,
			100,
			1,
			List.of(),
			List.of()
		);
	}
}
