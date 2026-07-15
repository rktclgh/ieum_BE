package shinhan.fibri.ieum.main.meeting.exception;

public class SchedulePermissionDeniedException extends RuntimeException {

	public SchedulePermissionDeniedException() {
		super("Only the schedule creator or meeting operator can delete this schedule");
	}
}
