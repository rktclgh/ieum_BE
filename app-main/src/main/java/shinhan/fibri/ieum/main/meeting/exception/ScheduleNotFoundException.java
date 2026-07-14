package shinhan.fibri.ieum.main.meeting.exception;

public class ScheduleNotFoundException extends RuntimeException {

	public ScheduleNotFoundException() {
		super("Schedule not found");
	}
}
