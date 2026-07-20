package shinhan.fibri.ieum.main.meeting.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;

/**
 * 캘린더 item. 날짜 미정 placeholder는 {@code date}/{@code startTime}/{@code endTime}이 모두 null이고
 * {@code status="unscheduled"}다. 시간 미정 일정은 placeholder가 아니라 날짜가 있는 정규 item이며
 * {@code timeUndecided=true}로 구분한다.
 */
public record MeetingCalendarItem(
	Long meetingId,
	Long scheduleId,
	String title,
	LocationSnapshot location,
	@JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
	@JsonFormat(pattern = "HH:mm") LocalTime startTime,
	@JsonFormat(pattern = "HH:mm") LocalTime endTime,
	boolean timeUndecided,
	OffsetDateTime startsAt,
	OffsetDateTime endsAt,
	String status,
	Long createdByUserId,
	boolean canDelete,
	Long roomId,
	boolean isHost
) {
}
