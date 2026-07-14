package shinhan.fibri.ieum.ai.question.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;

class LocalAnswerOutputParserTest {

	private final LocalAnswerOutputParser parser = new LocalAnswerOutputParser(new ObjectMapper());

	@Test
	void parsesStrictJsonAndPreservesAnswerOffsets() {
		String answer = "앞문으로 타고 뒷문으로 내립니다.";

		ParsedLocalAnswer parsed = parser.parse("""
			{"answer":"앞문으로 타고 뒷문으로 내립니다.","citations":[{"evidenceIndex":0,"startIndex":0,"endIndex":18}]}
			""", prompt(2));

		assertThat(parsed.answer()).isEqualTo(answer);
		assertThat(parsed.citations()).containsExactly(new AnswerCitation(0, 0, answer.length()));
	}

	@Test
	void rejectsCitationBoundariesInsideAUtf16SurrogatePair() {
		assertThatThrownBy(() -> parser.parse("""
			{"answer":"A😀B","citations":[{"evidenceIndex":0,"startIndex":2,"endIndex":3}]}
			""", prompt(1)))
			.isInstanceOfSatisfying(InvalidLocalAnswerOutputException.class, exception ->
				assertThat(exception.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.invalid_output)
			);
	}

	@Test
	void acceptsACompleteCharacterRangeAfterAUtf16SurrogatePair() {
		ParsedLocalAnswer parsed = parser.parse("""
			{"answer":"A😀B","citations":[{"evidenceIndex":0,"startIndex":3,"endIndex":4}]}
			""", prompt(1));

		assertThat(parsed.citations()).containsExactly(new AnswerCitation(0, 3, 4));
	}

	@Test
	void allowsOneEvidenceItemToSupportMultipleDistinctAnswerRanges() {
		ParsedLocalAnswer parsed = parser.parse("""
			{"answer":"앞문 승차, 뒷문 하차","citations":[{"evidenceIndex":0,"startIndex":0,"endIndex":5},{"evidenceIndex":0,"startIndex":7,"endIndex":12}]}
			""", prompt(1));

		assertThat(parsed.citations()).containsExactly(
			new AnswerCitation(0, 0, 5),
			new AnswerCitation(0, 7, 12)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidOutputs")
	void rejectsAnythingOutsideTheExactGroundedAnswerContract(
		String description,
		String output,
		LocalAnswerProviderFailureCode expectedCode
	) {
		assertThatThrownBy(() -> parser.parse(output, prompt(2)))
			.isInstanceOfSatisfying(InvalidLocalAnswerOutputException.class, exception ->
				assertThat(exception.failureCode()).isEqualTo(expectedCode)
			);
	}

	private static Stream<Arguments> invalidOutputs() {
		String answer = "앞문으로 타고 뒷문으로 내립니다.";
		String validCitation = "{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":" + answer.length() + "}";
		return Stream.of(
			Arguments.of("null", null, LocalAnswerProviderFailureCode.empty_response),
			Arguments.of("blank", "   ", LocalAnswerProviderFailureCode.empty_response),
			Arguments.of("markdown fence", "```json\n{\"answer\":\"답\",\"citations\":[]}\n```", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("trailing token", "{\"answer\":\"답\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":1}]}{}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("duplicate root key", "{\"answer\":\"답\",\"answer\":\"다른 답\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":1}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("unknown root field", "{\"answer\":\"답\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":1}],\"debug\":true}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("blank answer", "{\"answer\":\"   \",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":1}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("no citations", "{\"answer\":\"답\",\"citations\":[]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("too many citations", "{\"answer\":\"123456789\",\"citations\":[" +
				"{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":1},".repeat(8) +
				"{\"evidenceIndex\":0,\"startIndex\":8,\"endIndex\":9}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("unknown citation field", "{\"answer\":\"답\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":1,\"url\":\"https://example.com\"}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("fractional index", "{\"answer\":\"답\",\"citations\":[{\"evidenceIndex\":0.0,\"startIndex\":0,\"endIndex\":1}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("unknown evidence", "{\"answer\":\"답\",\"citations\":[{\"evidenceIndex\":2,\"startIndex\":0,\"endIndex\":1}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("negative start", "{\"answer\":\"답\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":-1,\"endIndex\":1}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("empty range", "{\"answer\":\"답\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":1,\"endIndex\":1}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("range outside answer", "{\"answer\":\"답\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":2}]}", LocalAnswerProviderFailureCode.invalid_output),
			Arguments.of("same answer range claimed twice", "{\"answer\":\"" + answer + "\",\"citations\":[" + validCitation + ",{\"evidenceIndex\":1,\"startIndex\":0,\"endIndex\":" + answer.length() + "}]}", LocalAnswerProviderFailureCode.invalid_output)
		);
	}

	private LocalAnswerPrompt prompt(int evidenceCount) {
		return new LocalAnswerPrompt(
			"질문",
			"내용",
			LocalAnswerRegion.empty(),
			java.util.stream.IntStream.range(0, evidenceCount)
				.mapToObj(index -> new LocalAnswerEvidence(index, "근거 " + index, "근거 내용 " + index, "curated_kg"))
				.toList()
		);
	}
}
