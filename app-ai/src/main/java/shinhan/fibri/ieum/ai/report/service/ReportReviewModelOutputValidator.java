package shinhan.fibri.ieum.ai.report.service;

import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;

@FunctionalInterface
public interface ReportReviewModelOutputValidator {

	ReportPolicyEvaluationResult evaluate(ReportModelReviewOutput modelOutput);
}
