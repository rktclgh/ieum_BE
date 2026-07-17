package shinhan.fibri.ieum.main.meeting.dto;

import java.time.OffsetDateTime;

public record MeetingScheduleItem(
	Long scheduleId,
	String title,
	String locationName,
	OffsetDateTime startsAt,
	OffsetDateTime endsAt,
	String status,
	Long createdByUserId,
	boolean canEdit,
	boolean canDelete,
	boolean canReport
) {
	public MeetingScheduleItem(
		Long scheduleId,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		String status,
		Long createdByUserId,
		boolean canDelete
	) {
		this(scheduleId, null, null, startsAt, endsAt, status, createdByUserId, false, canDelete, false);
	}
}
