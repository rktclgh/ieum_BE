package shinhan.fibri.ieum.main.report.ai.service;

import java.time.Duration;
import shinhan.fibri.ieum.main.report.repository.ReportAiReviewResult;

public record MappedReportAiReview(ReportAiReviewResult result, Duration automaticSanctionDuration) {

	public boolean requiresAutomaticSanction() {
		return automaticSanctionDuration != null;
	}
}
