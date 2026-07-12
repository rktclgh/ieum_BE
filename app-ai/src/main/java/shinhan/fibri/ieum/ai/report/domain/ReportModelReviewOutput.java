package shinhan.fibri.ieum.ai.report.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ReportModelReviewOutput(List<ReportModelRuleMatch> matchedRules, boolean uncertain) {

	public ReportModelReviewOutput {
		if (matchedRules == null) {
			throw new IllegalArgumentException("matchedRules must not be null");
		}
		Set<String> ruleCodes = new HashSet<>();
		for (ReportModelRuleMatch match : matchedRules) {
			if (match == null || !ruleCodes.add(match.ruleCode())) {
				throw new IllegalArgumentException("ruleCode must be unique within model output");
			}
		}
		matchedRules = List.copyOf(matchedRules);
	}
}
