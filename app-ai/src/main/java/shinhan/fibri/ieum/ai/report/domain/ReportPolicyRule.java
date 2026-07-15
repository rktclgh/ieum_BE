package shinhan.fibri.ieum.ai.report.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record ReportPolicyRule(
	String ruleCode,
	String title,
	String category,
	String criteria,
	ReportPolicyDecision decision,
	ReportPolicySeverity severity,
	Integer automaticSanctionDays,
	BigDecimal minConfidence,
	ReportEvidenceType evidenceType,
	int priority,
	int revision,
	List<String> positiveExamples,
	List<String> negativeExamples
) {

	private static final Pattern RULE_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{2,99}$");

	public ReportPolicyRule {
		if (ruleCode == null || !RULE_CODE.matcher(ruleCode).matches()) {
			throw new IllegalArgumentException("ruleCode must be an uppercase policy code");
		}
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title must not be blank");
		}
		if (category == null || category.isBlank()) {
			throw new IllegalArgumentException("category must not be blank");
		}
		if (criteria == null || criteria.isBlank()) {
			throw new IllegalArgumentException("criteria must not be blank");
		}
		Objects.requireNonNull(decision, "decision must not be null");
		Objects.requireNonNull(severity, "severity must not be null");
		Objects.requireNonNull(evidenceType, "evidenceType must not be null");
		if (minConfidence == null || minConfidence.compareTo(BigDecimal.ZERO) < 0 || minConfidence.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("minConfidence must be between 0 and 1");
		}
		if (priority < -1000 || priority > 1000) {
			throw new IllegalArgumentException("priority must be between -1000 and 1000");
		}
		if (revision < 1) {
			throw new IllegalArgumentException("revision must be positive");
		}
		positiveExamples = immutableExamples(positiveExamples, "positiveExamples");
		negativeExamples = immutableExamples(negativeExamples, "negativeExamples");
		if (decision == ReportPolicyDecision.suspend && !severity.isAtLeastHigh()) {
			throw new IllegalArgumentException("suspend rules require high or critical severity");
		}
		if (automaticSanctionDays != null) {
			if (decision != ReportPolicyDecision.suspend) {
				throw new IllegalArgumentException("automaticSanctionDays requires a suspend rule");
			}
			if (automaticSanctionDays < 1 || automaticSanctionDays > 365) {
				throw new IllegalArgumentException("automaticSanctionDays must be between 1 and 365");
			}
		}
		if (decision == ReportPolicyDecision.normal && severity != ReportPolicySeverity.low) {
			throw new IllegalArgumentException("normal rules require low severity");
		}
	}

	public ReportPolicyRule(
		String ruleCode,
		String title,
		String category,
		String criteria,
		ReportPolicyDecision decision,
		ReportPolicySeverity severity,
		BigDecimal minConfidence,
		ReportEvidenceType evidenceType,
		int priority,
		int revision,
		List<String> positiveExamples,
		List<String> negativeExamples
	) {
		this(
			ruleCode,
			title,
			category,
			criteria,
			decision,
			severity,
			null,
			minConfidence,
			evidenceType,
			priority,
			revision,
			positiveExamples,
			negativeExamples
		);
	}

	private static List<String> immutableExamples(List<String> examples, String field) {
		if (examples == null || examples.stream().anyMatch(example -> example == null || example.isBlank())) {
			throw new IllegalArgumentException(field + " must contain nonblank examples");
		}
		return List.copyOf(examples);
	}
}
