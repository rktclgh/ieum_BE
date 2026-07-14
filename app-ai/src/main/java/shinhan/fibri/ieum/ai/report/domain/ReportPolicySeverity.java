package shinhan.fibri.ieum.ai.report.domain;

public enum ReportPolicySeverity {
	low,
	medium,
	high,
	critical;

	public boolean isAtLeastHigh() {
		return this == high || this == critical;
	}
}
