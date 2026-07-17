package shinhan.fibri.ieum.ai.question.generation;

public record UngroundedAnswer(
	String content,
	String provider,
	String model,
	String promptVersion
) {

	public UngroundedAnswer {
		content = required(content, "content", 20_000);
		provider = required(provider, "provider", 40);
		model = required(model, "model", 120);
		promptVersion = required(promptVersion, "promptVersion", 80);
	}

	private static String required(String value, String field, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		String normalized = value.trim();
		if (normalized.length() > maxLength) {
			throw new IllegalArgumentException(field + " is too long");
		}
		return normalized;
	}
}
