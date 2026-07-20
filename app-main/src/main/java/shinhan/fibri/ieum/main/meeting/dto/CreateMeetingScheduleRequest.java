package shinhan.fibri.ieum.main.meeting.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 모임 생성 시의 일정 입력. 날짜와 시각은 독립적으로 미정일 수 있다(KST 해석).
 *
 * <ul>
 *   <li>one_time — {@code date} 필수. {@code startTime} 생략 = 시간 미정</li>
 *   <li>recurring — 날짜는 recurrenceRule이 관리하므로 {@code date} 금지, {@code startTime} 필수</li>
 * </ul>
 *
 * 조합 검증은 서비스가 필드 경로와 함께 수행한다(MeetingService#validateCreateSchedule).
 */
public record CreateMeetingScheduleRequest(
	@JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
	@JsonFormat(pattern = "HH:mm") LocalTime startTime,
	@JsonFormat(pattern = "HH:mm") LocalTime endTime
) {
}
