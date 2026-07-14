package shinhan.fibri.ieum.main.meeting.exception;

public class MeetingNotOpenException extends RuntimeException {

	public MeetingNotOpenException() {
		super("Meeting is not open");
	}
}
