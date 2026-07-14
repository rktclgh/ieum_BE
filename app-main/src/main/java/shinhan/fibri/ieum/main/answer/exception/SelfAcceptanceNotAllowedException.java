package shinhan.fibri.ieum.main.answer.exception;

public class SelfAcceptanceNotAllowedException extends RuntimeException {

	public SelfAcceptanceNotAllowedException() {
		super("Self acceptance is not allowed");
	}
}
