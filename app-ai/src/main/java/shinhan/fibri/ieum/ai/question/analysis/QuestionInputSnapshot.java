package shinhan.fibri.ieum.ai.question.analysis;

import java.util.Objects;

public record QuestionInputSnapshot(
	String title,
	String content,
	StoredLocationSnapshot location
) {

	public QuestionInputSnapshot {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title must not be blank");
		}
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
		Objects.requireNonNull(location, "location must not be null");
	}
}
