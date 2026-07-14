package shinhan.fibri.ieum.main.meeting.exception;

public class ParticipantNotFoundException extends RuntimeException {

	public ParticipantNotFoundException() {
		super("Participant not found");
	}
}
