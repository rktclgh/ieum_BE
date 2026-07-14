package shinhan.fibri.ieum.main.meeting.dto;

import java.time.LocalDate;
import java.util.List;

public record MeetingDetailRecurrenceRuleResponse(
	String frequency,
	int intervalValue,
	List<Integer> daysOfWeek,
	Integer dayOfMonth,
	LocalDate startsOn,
	LocalDate endsOn,
	Integer maxOccurrences,
	String timezone
) {
}
