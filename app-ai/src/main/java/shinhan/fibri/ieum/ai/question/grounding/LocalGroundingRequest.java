package shinhan.fibri.ieum.ai.question.grounding;

import java.util.Objects;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;
import shinhan.fibri.ieum.ai.question.generation.GeneratedAnswer;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerPrompt;

public record LocalGroundingRequest(
	LocalAnswerPrompt prompt,
	GeneratedAnswer candidate
) {

	public LocalGroundingRequest {
		prompt = Objects.requireNonNull(prompt, "prompt must not be null");
		candidate = Objects.requireNonNull(candidate, "candidate must not be null");
		for (AnswerCitation citation : candidate.citations()) {
			if (citation.evidenceIndex() >= prompt.evidence().size()) {
				throw new IllegalArgumentException("candidate citation evidenceIndex is outside prompt evidence");
			}
		}
	}
}
