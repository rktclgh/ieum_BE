package shinhan.fibri.ieum.main.meeting.dto;

import java.util.List;

public record MeetingCalendarResponse(
	List<MeetingCalendarItem> items
) {
}
