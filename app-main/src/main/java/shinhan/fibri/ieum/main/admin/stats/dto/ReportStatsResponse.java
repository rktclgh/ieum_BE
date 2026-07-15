package shinhan.fibri.ieum.main.admin.stats.dto;

import java.time.LocalDate;

public record ReportStatsResponse(
	LocalDate from,
	LocalDate to,
	long reportCount,
	long aiReviewedCount,
	long confirmedCount,
	long dismissedCount,
	long sanctionCount
) {
}
