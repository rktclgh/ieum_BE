package shinhan.fibri.ieum.ai.knowledge.embedding;

public final class KnowledgeDocumentEmbeddingTextFormatter {

	public String format(String title, String content) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}

		String formattedTitle = title == null || title.isBlank() ? "none" : title;
		return "title: " + formattedTitle + " | text: " + content;
	}
}
