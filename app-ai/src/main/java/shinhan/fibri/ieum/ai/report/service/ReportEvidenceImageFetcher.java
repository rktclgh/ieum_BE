package shinhan.fibri.ieum.ai.report.service;

import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewImage;

public interface ReportEvidenceImageFetcher {

	VerifiedReportEvidenceImage download(ReportReviewImage image, long maxAllowedBytes);
}
