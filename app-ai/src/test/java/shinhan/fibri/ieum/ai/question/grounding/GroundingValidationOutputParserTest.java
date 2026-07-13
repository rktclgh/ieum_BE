package shinhan.fibri.ieum.ai.question.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GroundingValidationOutputParserTest {

	private final GroundingValidationOutputParser parser =
		new GroundingValidationOutputParser(new ObjectMapper());

	@Test
	void parsesTheExactSupportedGroundingContract() {
		GroundingValidation validation = parser.parse("""
			{"supported":true,"score":0.93,"unsupportedClaims":[]}
			""");

		assertThat(validation.supported()).isTrue();
		assertThat(validation.score()).isEqualByComparingTo("0.93");
		assertThat(validation.unsupportedClaims()).isEmpty();
	}

	@Test
	void parsesAnUnsupportedVerdictWithBoundedClaims() {
		GroundingValidation validation = parser.parse("""
			{"supported":false,"score":0.42,"unsupportedClaims":["교통카드 사용 주장은 근거가 없습니다."]}
			""");

		assertThat(validation.supported()).isFalse();
		assertThat(validation.unsupportedClaims())
			.containsExactly("교통카드 사용 주장은 근거가 없습니다.");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidOutputs")
	void rejectsAnythingOutsideTheExactGroundingValidationContract(String description, String output) {
		assertThatThrownBy(() -> parser.parse(output))
			.isInstanceOf(InvalidGroundingValidationOutputException.class)
			.hasMessage("Invalid grounding validation output");
	}

	private static Stream<Arguments> invalidOutputs() {
		return Stream.of(
			Arguments.of("null", null),
			Arguments.of("blank", "   "),
			Arguments.of("markdown", "```json\n{\"supported\":true,\"score\":1,\"unsupportedClaims\":[]}\n```"),
			Arguments.of("trailing token", "{\"supported\":true,\"score\":1,\"unsupportedClaims\":[]}{}"),
			Arguments.of("duplicate root key", "{\"supported\":true,\"supported\":false,\"score\":1,\"unsupportedClaims\":[]}"),
			Arguments.of("unknown root field", "{\"supported\":true,\"score\":1,\"unsupportedClaims\":[],\"debug\":true}"),
			Arguments.of("missing root field", "{\"supported\":true,\"score\":1}"),
			Arguments.of("non boolean supported", "{\"supported\":\"true\",\"score\":1,\"unsupportedClaims\":[]}"),
			Arguments.of("non numeric score", "{\"supported\":true,\"score\":\"1\",\"unsupportedClaims\":[]}"),
			Arguments.of("negative score", "{\"supported\":true,\"score\":-0.01,\"unsupportedClaims\":[]}"),
			Arguments.of("score above one", "{\"supported\":true,\"score\":1.01,\"unsupportedClaims\":[]}"),
			Arguments.of("claims not array", "{\"supported\":true,\"score\":1,\"unsupportedClaims\":null}"),
			Arguments.of("supported with claim", "{\"supported\":true,\"score\":1,\"unsupportedClaims\":[\"claim\"]}"),
			Arguments.of("unsupported without claim", "{\"supported\":false,\"score\":0,\"unsupportedClaims\":[]}"),
			Arguments.of("blank claim", "{\"supported\":false,\"score\":0,\"unsupportedClaims\":[\"   \"]}"),
			Arguments.of("non textual claim", "{\"supported\":false,\"score\":0,\"unsupportedClaims\":[1]}"),
			Arguments.of("too many claims", "{\"supported\":false,\"score\":0,\"unsupportedClaims\":[" +
				"\"claim\",".repeat(8) + "\"claim\"]}"),
			Arguments.of("claim too long", "{\"supported\":false,\"score\":0,\"unsupportedClaims\":[\"" +
				"가".repeat(501) + "\"]}")
		);
	}
}
