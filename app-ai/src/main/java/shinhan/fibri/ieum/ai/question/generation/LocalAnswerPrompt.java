package shinhan.fibri.ieum.ai.question.generation;

import java.util.List;
import java.util.Objects;

public record LocalAnswerPrompt(
	String title,
	String content,
	LocalAnswerRegion coarseRegion,
	List<LocalAnswerEvidence> evidence
) {

	private static final int MAX_EVIDENCE = 8;

	public LocalAnswerPrompt {
		title = required(title, "title");
		content = required(content, "content");
		coarseRegion = Objects.requireNonNull(coarseRegion, "coarseRegion must not be null");
		Objects.requireNonNull(evidence, "evidence must not be null");
		evidence = List.copyOf(evidence);
		if (evidence.isEmpty() || evidence.size() > MAX_EVIDENCE) {
			throw new IllegalArgumentException("evidence must contain 1 to 8 items");
		}
		for (int index = 0; index < evidence.size(); index++) {
			LocalAnswerEvidence item = Objects.requireNonNull(evidence.get(index), "evidence item must not be null");
			if (item.evidenceIndex() != index) {
				throw new IllegalArgumentException("evidenceIndex must be sequential and zero-based");
			}
		}
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}
}
