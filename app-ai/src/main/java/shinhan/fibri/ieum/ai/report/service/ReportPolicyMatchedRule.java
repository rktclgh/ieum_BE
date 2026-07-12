package shinhan.fibri.ieum.ai.report.service;

public record ReportPolicyMatchedRule(String ruleCode, int revision) {

	public ReportPolicyMatchedRule {
		if (ruleCode == null || ruleCode.isBlank()) {
			throw new IllegalArgumentException("ruleCode must not be blank");
		}
		if (revision < 1) {
			throw new IllegalArgumentException("revision must be positive");
		}
	}
}
