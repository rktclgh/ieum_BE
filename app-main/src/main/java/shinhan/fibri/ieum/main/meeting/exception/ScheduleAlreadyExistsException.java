package shinhan.fibri.ieum.main.meeting.exception;

public class ScheduleAlreadyExistsException extends RuntimeException {

	public ScheduleAlreadyExistsException() {
		super("Active schedule already exists");
	}
}
