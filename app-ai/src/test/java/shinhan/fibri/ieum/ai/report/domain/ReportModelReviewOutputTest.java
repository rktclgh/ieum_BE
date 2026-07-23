package shinhan.fibri.ieum.ai.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportModelReviewOutputTest {

	@Test
	void keepsAUniqueImmutableSetOfModelMatches() {
		ReportModelRuleMatch match = match("CONTENT-ABUSE-001");

		ReportModelReviewOutput output = new ReportModelReviewOutput(List.of(match), false);

		assertThat(output.uncertain()).isFalse();
		assertThat(output.matchedRules()).containsExactly(match);
		assertThatThrownBy(() -> output.matchedRules().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsDuplicateModelRuleMatches() {
		assertThatThrownBy(() -> new ReportModelReviewOutput(List.of(
			match("CONTENT-ABUSE-001"),
			match("CONTENT-ABUSE-001")
		), false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ruleCode");
	}

	@Test
	void rejectsConfidenceOutsideThePolicyRange() {
		assertThatThrownBy(() -> new ReportModelRuleMatch(
			"CONTENT-ABUSE-001", new BigDecimal("1.0001"), List.of(2L), "위반 근거"
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confidence");
	}

	@Test
	void acceptsPolicyConfidenceBoundaries() {
		assertThat(new ReportModelRuleMatch("CONTENT-ABUSE-001", BigDecimal.ZERO, List.of(1L), "위반 근거").confidence())
			.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(new ReportModelRuleMatch("CONTENT-ABUSE-002", BigDecimal.ONE, List.of(2L), "위반 근거").confidence())
			.isEqualByComparingTo(BigDecimal.ONE);
	}

	@Test
	void rejectsMalformedModelMatchFields() {
		assertThatThrownBy(() -> new ReportModelRuleMatch("invalid", new BigDecimal("0.9"), List.of(1L), "위반 근거"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ruleCode");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", null, List.of(1L), "위반 근거"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confidence");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("-0.0001"), List.of(1L), "위반 근거"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confidence");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("0.9"), null, "위반 근거"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("evidenceMessageIds");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("0.9"), List.of(), "위반 근거"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("evidenceMessageIds");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("0.9"), List.of(1L, 1L), "위반 근거"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("evidenceMessageIds");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("0.9"), List.of(0L), "위반 근거"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("evidenceMessageIds");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("0.9"), List.of(1L), " "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("reason");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("0.9"), List.of(1L), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("reason");
		assertThatThrownBy(() -> new ReportModelRuleMatch("CONTENT-ABUSE-001", new BigDecimal("0.9"), List.of(1L), "clear abuse"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Korean");
	}

	@Test
	void copiesEvidenceAndMatchListsDefensively() {
		List<Long> mutableEvidenceIds = new ArrayList<>(List.of(2L));
		ReportModelRuleMatch match = new ReportModelRuleMatch(
			"CONTENT-ABUSE-001", new BigDecimal("0.9"), mutableEvidenceIds, "욕설 근거"
		);
		List<ReportModelRuleMatch> mutableMatches = new ArrayList<>(List.of(match));
		ReportModelReviewOutput output = new ReportModelReviewOutput(mutableMatches, false);

		mutableEvidenceIds.clear();
		mutableMatches.clear();

		assertThat(match.evidenceMessageIds()).containsExactly(2L);
		assertThatThrownBy(() -> match.evidenceMessageIds().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThat(output.matchedRules()).containsExactly(match);
	}

	@Test
	void rejectsNullMatchListsAndItems() {
		assertThatThrownBy(() -> new ReportModelReviewOutput(null, false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("matchedRules");
		assertThatThrownBy(() -> new ReportModelReviewOutput(Arrays.asList((ReportModelRuleMatch) null), false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ruleCode");
	}

	private ReportModelRuleMatch match(String ruleCode) {
		return new ReportModelRuleMatch(ruleCode, new BigDecimal("0.9000"), List.of(2L), "명확한 위반");
	}
}
