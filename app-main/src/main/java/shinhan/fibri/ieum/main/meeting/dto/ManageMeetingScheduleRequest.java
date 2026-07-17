package shinhan.fibri.ieum.main.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record ManageMeetingScheduleRequest(
	@NotBlank @Size(max = 100) String title,
	@NotBlank @Size(max = 200) String locationName,
	@NotNull OffsetDateTime startsAt,
	OffsetDateTime endsAt
) {
}
