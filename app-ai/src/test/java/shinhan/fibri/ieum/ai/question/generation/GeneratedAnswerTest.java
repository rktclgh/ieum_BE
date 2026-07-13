package shinhan.fibri.ieum.ai.question.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;

class GeneratedAnswerTest {

	@Test
	void acceptsACompleteCharacterRangeAfterAUtf16SurrogatePair() {
		GeneratedAnswer answer = new GeneratedAnswer(
			"A😀B",
			List.of(new AnswerCitation(0, 3, 4)),
			"bedrock",
			"amazon.nova-micro-v1:0",
			"question-local-answer-v1",
			Instant.parse("2026-07-13T10:15:30Z"),
			null,
			null,
			null,
			null
		);

		assertThat(answer.citations()).containsExactly(new AnswerCitation(0, 3, 4));
	}

	@Test
	void rejectsCitationBoundariesInsideAUtf16SurrogatePair() {
		assertThatThrownBy(() -> new GeneratedAnswer(
			"A😀B",
			List.of(new AnswerCitation(0, 0, 2)),
			"bedrock",
			"amazon.nova-micro-v1:0",
			"question-local-answer-v1",
			Instant.parse("2026-07-13T10:15:30Z"),
			null,
			null,
			null,
			null
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("surrogate");
	}
}
