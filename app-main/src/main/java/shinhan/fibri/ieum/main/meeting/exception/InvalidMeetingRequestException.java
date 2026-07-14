package shinhan.fibri.ieum.main.meeting.exception;

public class InvalidMeetingRequestException extends RuntimeException {

	private final String code;
	private final String field;

	public InvalidMeetingRequestException(String code, String field, String message) {
		super(message);
		this.code = code;
		this.field = field;
	}

	public String code() {
		return code;
	}

	public String field() {
		return field;
	}
}
