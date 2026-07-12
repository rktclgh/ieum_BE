package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReportReviewModelOutputParserTest {

	private final ReportReviewModelOutputParser parser = new ReportReviewModelOutputParser();

	@Test
	void parsesTheStrictModelReviewOutputShape() {
		var output = parser.parse("""
			{
			  "matchedRules": [{
			    "ruleCode": "CONTENT-ABUSE-001",
			    "confidence": 0.99,
			    "evidenceMessageIds": [2, 3],
			    "reason": "abuse found"
			  }],
			  "uncertain": false
			}
			""");

		assertThat(output.uncertain()).isFalse();
		assertThat(output.matchedRules()).singleElement().satisfies(match -> {
			assertThat(match.ruleCode()).isEqualTo("CONTENT-ABUSE-001");
			assertThat(match.confidence()).isEqualByComparingTo("0.99");
			assertThat(match.evidenceMessageIds()).containsExactly(2L, 3L);
		});
	}

	@Test
	void rejectsMalformedOrUnexpectedModelOutput() {
		assertInvalid("{\"matchedRules\": [], \"uncertain\": false, \"extra\": true}");
		assertInvalid("{\"matchedRules\": [{\"ruleCode\": \"RULE\", \"confidence\": \"0.9\", \"evidenceMessageIds\": [2], \"reason\": \"x\"}], \"uncertain\": false}");
		assertInvalid("not json");
	}

	@Test
	void rejectsDuplicateRootAndMatchFields() {
		assertInvalid("{\"matchedRules\": [], \"matchedRules\": [], \"uncertain\": false}");
		assertInvalid("""
			{
			  "matchedRules": [{
			    "ruleCode": "CONTENT-ABUSE-001",
			    "ruleCode": "CONTENT-OTHER-001",
			    "confidence": 0.99,
			    "evidenceMessageIds": [2],
			    "reason": "abuse found"
			  }],
			  "uncertain": false
			}
			""");
	}

	private void assertInvalid(String json) {
		assertThatThrownBy(() -> parser.parse(json))
			.isInstanceOf(InvalidReportModelOutputException.class);
	}
}
