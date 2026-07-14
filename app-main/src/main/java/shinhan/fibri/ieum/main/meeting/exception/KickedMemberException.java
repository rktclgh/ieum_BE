package shinhan.fibri.ieum.main.meeting.exception;

public class KickedMemberException extends RuntimeException {

	public KickedMemberException() {
		super("Kicked member cannot join again");
	}
}
