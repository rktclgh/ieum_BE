package shinhan.fibri.ieum.main.report.ai.service;

public record ReportAiApplyOutcome(boolean transitioned, String decision, boolean sanctioned) {

	public static ReportAiApplyOutcome completed(String decision, boolean sanctioned) {
		return new ReportAiApplyOutcome(true, decision, sanctioned);
	}

	public static ReportAiApplyOutcome stale(String decision) {
		return new ReportAiApplyOutcome(false, decision, false);
	}
}
