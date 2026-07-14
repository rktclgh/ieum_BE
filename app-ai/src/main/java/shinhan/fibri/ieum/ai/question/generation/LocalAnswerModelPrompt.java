package shinhan.fibri.ieum.ai.question.generation;

record LocalAnswerModelPrompt(String systemInstruction, String userInstruction) {

	LocalAnswerModelPrompt {
		if (systemInstruction == null || systemInstruction.isBlank()) {
			throw new IllegalArgumentException("systemInstruction must not be blank");
		}
		if (userInstruction == null || userInstruction.isBlank()) {
			throw new IllegalArgumentException("userInstruction must not be blank");
		}
	}
}
