package shinhan.fibri.ieum.main.admin.report.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.report.domain.ReportAiReviewState;

public record AdminReportAiSummary(
	ReportAiReviewState reviewState,
	String recommendation,
	AdminReportDecision decision,
	BigDecimal confidence,
	OffsetDateTime reviewedAt
) {
}
