package shinhan.fibri.ieum.main.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import shinhan.fibri.ieum.main.meeting.domain.RecurrenceFrequency;

public record CreateMeetingRecurrenceRuleRequest(
	@NotNull RecurrenceFrequency frequency,
	@NotNull @Min(1) @Max(12) Integer intervalValue,
	List<Integer> daysOfWeek,
	Integer dayOfMonth,
	@NotNull LocalDate startsOn,
	LocalDate endsOn,
	@Min(1) @Max(366) Integer maxOccurrences,
	String timezone
) {
}
