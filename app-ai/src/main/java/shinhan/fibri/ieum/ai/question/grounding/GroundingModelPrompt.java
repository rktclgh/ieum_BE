package shinhan.fibri.ieum.ai.question.grounding;

record GroundingModelPrompt(String systemInstruction, String userInstruction) {

	GroundingModelPrompt {
		if (systemInstruction == null || systemInstruction.isBlank()) {
			throw new IllegalArgumentException("systemInstruction must not be blank");
		}
		if (userInstruction == null || userInstruction.isBlank()) {
			throw new IllegalArgumentException("userInstruction must not be blank");
		}
	}
}
