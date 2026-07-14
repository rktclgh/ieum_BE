package shinhan.fibri.ieum.main.ai.client;

import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

public interface AiServiceClient {

	ReportReviewResponse review(ReportReviewRequest request);
}
