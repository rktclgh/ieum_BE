package shinhan.fibri.ieum.ai.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportPolicySnapshotTest {

	@Test
	void keepsAnImmutableUniqueRuleSnapshot() {
		ReportPolicyRule rule = rule("CONTENT-ABUSE-001");

		ReportPolicySnapshot snapshot = new ReportPolicySnapshot("a".repeat(64), List.of(rule));

		assertThat(snapshot.policySetHash()).isEqualTo("a".repeat(64));
		assertThat(snapshot.rules()).containsExactly(rule);
	}

	@Test
	void rejectsDuplicateRuleCodes() {
		assertThatThrownBy(() -> new ReportPolicySnapshot("a".repeat(64), List.of(
			rule("CONTENT-ABUSE-001"),
			rule("CONTENT-ABUSE-001")
		)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ruleCode");
	}

	@Test
	void acceptsAnEmptyPolicySnapshot() {
		ReportPolicySnapshot snapshot = new ReportPolicySnapshot("a".repeat(64), List.of());

		assertThat(snapshot.rules()).isEmpty();
	}

	@Test
	void rejectsAHashThatIsNotLowercaseSha256() {
		assertThatThrownBy(() -> new ReportPolicySnapshot("A".repeat(64), List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("policySetHash");
	}

	@Test
	void rejectsNullRulesAndNullRuleItems() {
		assertThatThrownBy(() -> new ReportPolicySnapshot("a".repeat(64), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("rules");

		assertThatThrownBy(() -> new ReportPolicySnapshot("a".repeat(64), Arrays.asList((ReportPolicyRule) null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ruleCode");
	}

	@Test
	void copiesTheRuleListDefensively() {
		List<ReportPolicyRule> mutableRules = new ArrayList<>(List.of(rule("CONTENT-ABUSE-001")));
		ReportPolicySnapshot snapshot = new ReportPolicySnapshot("a".repeat(64), mutableRules);

		mutableRules.clear();

		assertThat(snapshot.rules()).hasSize(1);
		assertThatThrownBy(() -> snapshot.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	private ReportPolicyRule rule(String ruleCode) {
		return new ReportPolicyRule(
			ruleCode,
			"abuse",
			ReportPolicyDecision.suspend,
			ReportPolicySeverity.high,
			new BigDecimal("0.8500"),
			ReportEvidenceType.text,
			100,
			1
		);
	}
}
