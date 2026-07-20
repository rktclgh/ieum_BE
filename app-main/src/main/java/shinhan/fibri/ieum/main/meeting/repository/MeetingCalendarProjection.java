package shinhan.fibri.ieum.main.meeting.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public interface MeetingCalendarProjection {

	Long getMeetingId();

	Long getScheduleId();

	String getTitle();


	double getLatitude();

	double getLongitude();

	String getAddress();

	String getDetailAddress();

	String getLabel();

	LocalDate getStartsOn();

	LocalTime getStartTime();

	LocalTime getEndTime();

	Instant getStartsAt();

	Instant getEndsAt();

	String getStatus();

	Long getCreatedByUserId();

	Long getRoomId();

	Boolean getHost();
}
