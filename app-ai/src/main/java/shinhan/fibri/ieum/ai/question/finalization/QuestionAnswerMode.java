package shinhan.fibri.ieum.ai.question.finalization;

public enum QuestionAnswerMode {
	LOCAL_GROUNDED("local_grounded"),
	WEB_GROUNDED("web_grounded");

	private final String databaseValue;

	QuestionAnswerMode(String databaseValue) {
		this.databaseValue = databaseValue;
	}

	String databaseValue() {
		return databaseValue;
	}
}
