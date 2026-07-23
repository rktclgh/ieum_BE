package shinhan.fibri.ieum.ai.report.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record ReportModelRuleMatch(
	String ruleCode,
	BigDecimal confidence,
	List<Long> evidenceMessageIds,
	String reason
) {

	private static final Pattern RULE_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{2,99}$");
	private static final Pattern HANGUL = Pattern.compile("[가-힣]");

	public ReportModelRuleMatch {
		if (ruleCode == null || !RULE_CODE.matcher(ruleCode).matches()) {
			throw new IllegalArgumentException("ruleCode must be an uppercase policy code");
		}
		if (confidence == null || confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("confidence must be between 0 and 1");
		}
		if (evidenceMessageIds == null || evidenceMessageIds.isEmpty()) {
			throw new IllegalArgumentException("evidenceMessageIds must not be empty");
		}
		Set<Long> messageIds = new HashSet<>();
		for (Long messageId : evidenceMessageIds) {
			if (messageId == null || messageId < 1 || !messageIds.add(messageId)) {
				throw new IllegalArgumentException("evidenceMessageIds must contain unique positive IDs");
			}
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		if (!HANGUL.matcher(reason).find()) {
			throw new IllegalArgumentException("reason must be Korean and include Hangul");
		}
		evidenceMessageIds = List.copyOf(evidenceMessageIds);
	}
}
