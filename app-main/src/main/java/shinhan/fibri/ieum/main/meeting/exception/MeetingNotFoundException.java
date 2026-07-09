package shinhan.fibri.ieum.main.meeting.exception;

public class MeetingNotFoundException extends RuntimeException {

	public MeetingNotFoundException() {
		super("Meeting not found");
	}
}
