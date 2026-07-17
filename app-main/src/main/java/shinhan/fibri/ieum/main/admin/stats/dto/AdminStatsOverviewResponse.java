package shinhan.fibri.ieum.main.admin.stats.dto;

import java.time.LocalDate;
import java.util.List;

public record AdminStatsOverviewResponse(
	LocalDate from,
	LocalDate to,
	String bucket,
	Summary summary,
	List<AdminStatsDailySeriesResponse> series,
	AdminStatsQueueResponse queues
) {

	public record Summary(
		long signupCount,
		long activeUserCount,
		long suspensionCount,
		long questionCount,
		long humanAnswerCount,
		long acceptedHumanAnswerCount,
		double acceptedRate,
		long reportCount,
		long aiReviewedCount,
		long confirmedCount,
		long dismissedCount,
		long sanctionCount
	) {
	}
}
