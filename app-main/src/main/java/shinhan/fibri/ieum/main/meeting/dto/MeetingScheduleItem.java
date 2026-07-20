package shinhan.fibri.ieum.main.meeting.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 일정 응답. {@code date}/{@code startTime}/{@code endTime}이 정본이고
 * {@code startsAt}/{@code endsAt}은 하위 호환용 파생 캐시(deprecated)다.
 * {@code timeUndecided=true}면 FE는 시각 대신 "시간 미정"을 렌더한다.
 */
public record MeetingScheduleItem(
	Long scheduleId,
	String title,
	String locationName,
	@JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
	@JsonFormat(pattern = "HH:mm") LocalTime startTime,
	@JsonFormat(pattern = "HH:mm") LocalTime endTime,
	boolean timeUndecided,
	OffsetDateTime startsAt,
	OffsetDateTime endsAt,
	String status,
	Long createdByUserId,
	boolean canEdit,
	boolean canDelete,
	boolean canReport
) {
}
