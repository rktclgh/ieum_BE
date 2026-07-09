package shinhan.fibri.ieum.main.meeting.dto;

import java.time.OffsetDateTime;

public record MeetingScheduleItem(
	Long scheduleId,
	OffsetDateTime startsAt,
	OffsetDateTime endsAt,
	String status
) {
}
