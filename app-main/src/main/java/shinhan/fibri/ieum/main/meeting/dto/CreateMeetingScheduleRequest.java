package shinhan.fibri.ieum.main.meeting.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record CreateMeetingScheduleRequest(
	@NotNull @Future OffsetDateTime startsAt,
	OffsetDateTime endsAt
) {
}
