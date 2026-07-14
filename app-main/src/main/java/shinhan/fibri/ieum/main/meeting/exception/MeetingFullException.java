package shinhan.fibri.ieum.main.meeting.exception;

public class MeetingFullException extends RuntimeException {

	public MeetingFullException() {
		super("Meeting is full");
	}
}
