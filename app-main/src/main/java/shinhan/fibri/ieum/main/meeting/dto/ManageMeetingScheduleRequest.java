package shinhan.fibri.ieum.main.meeting.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 참여자가 등록·수정하는 일정. {@code startTime} 생략 = 시간 미정이며,
 * 수정 시 시간 확정 ↔ 미정 전환도 이 필드로 한다(KST 해석).
 */
public record ManageMeetingScheduleRequest(
	@NotBlank @Size(max = 100) String title,
	@NotBlank @Size(max = 200) String locationName,
	@NotNull @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
	@JsonFormat(pattern = "HH:mm") LocalTime startTime,
	@JsonFormat(pattern = "HH:mm") LocalTime endTime
) {
}
