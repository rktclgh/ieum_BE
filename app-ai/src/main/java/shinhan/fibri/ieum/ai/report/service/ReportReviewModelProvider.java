package shinhan.fibri.ieum.ai.report.service;

import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;

public interface ReportReviewModelProvider {

	String provider();

	String model();

	ReportModelReviewOutput review(PreparedReportReview preparedReview, ReportPolicySnapshot policySnapshot);
}
