package shinhan.fibri.ieum.main.admin.stats.dto;

import java.time.LocalDate;

public record AdminStatsDailySeriesResponse(
	LocalDate date,
	long signupCount,
	long activeUserCount,
	long questionCount,
	long humanAnswerCount,
	long acceptedHumanAnswerCount,
	long reportCount,
	long aiReviewedCount,
	long confirmedCount,
	long dismissedCount,
	long sanctionCount
) {
}
