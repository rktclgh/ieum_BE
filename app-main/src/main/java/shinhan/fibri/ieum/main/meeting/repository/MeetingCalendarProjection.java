package shinhan.fibri.ieum.main.meeting.repository;

import java.time.Instant;

public interface MeetingCalendarProjection {

	Long getMeetingId();

	Long getScheduleId();

	String getTitle();


	double getLatitude();

	double getLongitude();

	String getAddress();

	String getDetailAddress();

	String getLabel();

	Instant getStartsAt();

	Instant getEndsAt();

	String getStatus();

	Long getCreatedByUserId();

	Long getRoomId();

	Boolean getHost();
}
