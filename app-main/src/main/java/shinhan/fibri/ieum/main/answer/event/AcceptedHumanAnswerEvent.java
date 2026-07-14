package shinhan.fibri.ieum.main.answer.event;

public record AcceptedHumanAnswerEvent(Long answerId) {

	public AcceptedHumanAnswerEvent {
		if (answerId == null || answerId <= 0) {
			throw new IllegalArgumentException("answerId must be positive");
		}
	}
}
