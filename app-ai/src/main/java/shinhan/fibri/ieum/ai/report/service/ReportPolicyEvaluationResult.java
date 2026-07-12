package shinhan.fibri.ieum.ai.report.service;

import java.math.BigDecimal;
import java.util.List;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyDecision;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySeverity;

public record ReportPolicyEvaluationResult(
	ReportPolicyDecision decision,
	String category,
	ReportPolicySeverity severity,
	BigDecimal confidence,
	String reason,
	String sourceRuleCode,
	List<Long> evidenceMessageIds,
	List<ReportPolicyMatchedRule> matchedRules
) {

	public ReportPolicyEvaluationResult {
		evidenceMessageIds = List.copyOf(evidenceMessageIds);
		matchedRules = List.copyOf(matchedRules);
	}
}
