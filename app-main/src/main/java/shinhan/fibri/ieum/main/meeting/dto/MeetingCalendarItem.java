package shinhan.fibri.ieum.main.meeting.dto;

import java.time.OffsetDateTime;

public record MeetingCalendarItem(
	Long meetingId,
	Long scheduleId,
	String title,
	String placeName,
	OffsetDateTime startsAt,
	OffsetDateTime endsAt,
	String status,
	Long roomId,
	boolean isHost
) {
}
