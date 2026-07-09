package shinhan.fibri.ieum.main.meeting.dto;

import jakarta.validation.constraints.NotNull;

public record KickMeetingRequest(
	@NotNull
	Long userId
) {
}
