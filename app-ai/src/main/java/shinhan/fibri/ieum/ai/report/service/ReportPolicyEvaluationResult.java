package shinhan.fibri.ieum.ai.report.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
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

	private static final Pattern HANGUL = Pattern.compile("[가-힣]");

	public ReportPolicyEvaluationResult {
		if (reason == null || reason.isBlank() || !HANGUL.matcher(reason).find()) {
			throw new IllegalArgumentException("evaluation reason must be Korean");
		}
		evidenceMessageIds = List.copyOf(evidenceMessageIds);
		matchedRules = List.copyOf(matchedRules);
	}
}
