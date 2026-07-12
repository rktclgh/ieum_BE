package shinhan.fibri.ieum.ai.report.service;

import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;

public interface ReportReviewModelGateway {

	ReportReviewInference review(
		PreparedReportReview preparedReview,
		ReportPolicySnapshot policySnapshot,
		ReportReviewModelOutputValidator outputValidator
	);
}
