package shinhan.fibri.ieum.main.meeting.dto;

public record CreateMeetingResponse(
	Long meetingId,
	Long pinId,
	Long roomId,
	Long firstScheduleId
) {
	public CreateMeetingResponse(Long meetingId, Long pinId, Long roomId) {
		this(meetingId, pinId, roomId, null);
	}
}
