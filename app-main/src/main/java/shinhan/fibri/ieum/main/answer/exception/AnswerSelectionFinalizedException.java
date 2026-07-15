package shinhan.fibri.ieum.main.answer.exception;

public class AnswerSelectionFinalizedException extends RuntimeException {

	public AnswerSelectionFinalizedException() {
		super("Answer selection already finalized");
	}
}
