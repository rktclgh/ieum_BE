package shinhan.fibri.ieum.main.meeting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import shinhan.fibri.ieum.main.meeting.domain.MeetingType;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;

public record CreateMeetingRequest(
	@NotBlank @Size(max = 200) String title,
	@Size(max = 2000) String content,
	@NotNull MeetingType type,
	@NotNull @Valid LocationSnapshot location,
	@Valid CreateMeetingScheduleRequest schedule,
	@Valid CreateMeetingRecurrenceRuleRequest recurrenceRule,
	@NotNull @Min(2) @Max(99) Integer maxMembers,
	UUID imageFileId
) {
}
