package shinhan.fibri.ieum.main.ai.outbox.service;

/**
 * {@code ai.question-answer.dispatch} 메시지의 {@code reason} 와이어 값.
 * {@link shinhan.fibri.ieum.common.ai.job.dto.QuestionAnswerDispatchMessage}의 허용 집합과 일치해야 한다.
 */
public enum Reason {

	CREATED("created"),
	REGENERATED("regenerated");

	private final String value;

	Reason(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}
}
