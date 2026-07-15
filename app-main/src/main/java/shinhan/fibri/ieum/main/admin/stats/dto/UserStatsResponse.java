package shinhan.fibri.ieum.main.admin.stats.dto;

import java.time.LocalDate;

public record UserStatsResponse(
	LocalDate from,
	LocalDate to,
	long signupCount,
	long activeUserCount,
	long suspendedUserCount
) {
}
