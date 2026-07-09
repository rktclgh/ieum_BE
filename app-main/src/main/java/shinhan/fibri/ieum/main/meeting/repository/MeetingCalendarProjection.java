package shinhan.fibri.ieum.main.meeting.repository;

import java.time.Instant;

public interface MeetingCalendarProjection {

	Long getMeetingId();

	Long getScheduleId();

	String getTitle();

	String getPlaceName();

	Instant getStartsAt();

	Instant getEndsAt();

	String getStatus();

	Long getRoomId();

	Boolean getHost();
}
