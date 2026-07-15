package shinhan.fibri.ieum.main.admin.stats.dto;

import java.time.LocalDate;

public record ContentStatsResponse(
	LocalDate from,
	LocalDate to,
	long pinCount,
	long questionCount,
	long meetingCount,
	long answerCount,
	double acceptedRate,
	long messageCount
) {
}
