package shinhan.fibri.ieum.main.meeting.exception;

public class NotMeetingMemberException extends RuntimeException {

	public NotMeetingMemberException() {
		super("Meeting member is required");
	}
}
