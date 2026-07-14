package shinhan.fibri.ieum.main.meeting.exception;

public class HostCannotLeaveException extends RuntimeException {

	public HostCannotLeaveException() {
		super("Host cannot leave meeting");
	}
}
