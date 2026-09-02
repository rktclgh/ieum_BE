package shinhan.fibri.ieum.common.ai.job;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * AI 작업 메시지의 {@code jobType} 와이어 값.
 */
public enum AiJobType {

	QUESTION_ANSWER_DISPATCH("question_answer_dispatch"),
	ACCEPTED_ANSWER_KNOWLEDGE_INGEST("accepted_answer_knowledge_ingest"),
	QUESTION_ANSWER_COMPLETED("question_answer_completed");

	private final String value;

	AiJobType(String value) {
		this.value = value;
	}

	@JsonValue
	public String value() {
		return value;
	}
}
