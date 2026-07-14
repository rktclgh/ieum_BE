package shinhan.fibri.ieum.main.meeting.exception;

public class NotHostException extends RuntimeException {

	public NotHostException() {
		super("Host permission is required");
	}
}
