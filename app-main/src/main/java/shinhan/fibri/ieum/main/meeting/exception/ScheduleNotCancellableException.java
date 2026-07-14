package shinhan.fibri.ieum.main.meeting.exception;

public class ScheduleNotCancellableException extends RuntimeException {

	public ScheduleNotCancellableException() {
		super("Schedule is not cancellable");
	}
}
