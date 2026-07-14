package shinhan.fibri.ieum.ai.question.generation;

import java.util.List;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;

public record ParsedLocalAnswer(String answer, List<AnswerCitation> citations) {

	public ParsedLocalAnswer {
		if (answer == null || answer.isBlank()) {
			throw new IllegalArgumentException("answer must not be blank");
		}
		citations = List.copyOf(citations);
	}
}
