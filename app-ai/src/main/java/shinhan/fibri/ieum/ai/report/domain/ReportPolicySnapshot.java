package shinhan.fibri.ieum.ai.report.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record ReportPolicySnapshot(String policySetHash, List<ReportPolicyRule> rules) {

	private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

	public ReportPolicySnapshot {
		if (policySetHash == null || !SHA_256_HEX.matcher(policySetHash).matches()) {
			throw new IllegalArgumentException("policySetHash must be a lowercase SHA-256 hash");
		}
		if (rules == null) {
			throw new IllegalArgumentException("rules must not be null");
		}
		Set<String> ruleCodes = new HashSet<>();
		for (ReportPolicyRule rule : rules) {
			if (rule == null || !ruleCodes.add(rule.ruleCode())) {
				throw new IllegalArgumentException("ruleCode must be unique within a policy snapshot");
			}
		}
		rules = List.copyOf(rules);
	}
}
