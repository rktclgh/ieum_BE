package shinhan.fibri.ieum.main.report.ai.service;

import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;
import shinhan.fibri.ieum.main.report.repository.ClaimedReport;

public interface ReportAiResultApplier {

	ReportAiApplyOutcome apply(ClaimedReport claimed, ReportReviewResponse response);
}
