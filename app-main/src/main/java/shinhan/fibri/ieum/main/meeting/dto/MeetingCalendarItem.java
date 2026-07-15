package shinhan.fibri.ieum.main.meeting.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;

public record MeetingCalendarItem(
	Long meetingId,
	Long scheduleId,
	String title,
	LocationSnapshot location,
	OffsetDateTime startsAt,
	OffsetDateTime endsAt,
	String status,
	Long createdByUserId,
	boolean canDelete,
	Long roomId,
	boolean isHost
) {
}
