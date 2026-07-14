package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialClaimCoverageValidatorTest {

	private final MaterialClaimCoverageValidator validator = new MaterialClaimCoverageValidator();

	@Test
	void acceptsACompletelyCoveredKoreanSentenceWithUncoveredTerminalPunctuation() {
		String answer = "서울 버스는 앞문으로 탑니다.";
		int end = answer.indexOf('.');

		assertThat(validator.coversEveryMaterialSentence(
			answer,
			List.of(citation(answer, 0, end))
		)).isTrue();
	}

	@Test
	void rejectsWhenOnlyPartOfAWordBearingSentenceIsCovered() {
		String answer = "서울 버스는 앞문으로 탑니다.";

		assertThat(validator.coversEveryMaterialSentence(
			answer,
			List.of(citation(answer, 0, 2))
		)).isFalse();
	}

	@Test
	void rejectsAnUnsupportedSecondSentence() {
		String answer = "첫 문장. 둘째 문장.";
		int firstSentenceEnd = answer.indexOf('.');

		assertThat(validator.coversEveryMaterialSentence(
			answer,
			List.of(citation(answer, 0, firstSentenceEnd))
		)).isFalse();
	}

	@Test
	void handlesNewlineListsAndEmojiAdjacentCitationRanges() {
		String answer = "- 🙂서울 버스\n- 수원 지하철!";
		int firstStart = answer.indexOf("서울");
		int firstEnd = answer.indexOf('\n');
		int secondStart = answer.indexOf("수원");
		int secondEnd = answer.indexOf('!');

		assertThat(validator.coversEveryMaterialSentence(
			answer,
			List.of(
				citation(answer, firstStart, firstEnd),
				citation(answer, secondStart, secondEnd)
			)
		)).isTrue();
	}

	@Test
	void ignoresSegmentsContainingOnlyWhitespaceBulletsPunctuationAndEmoji() {
		String answer = "• 🙂 !\n？";

		assertThat(validator.coversEveryMaterialSentence(answer, List.of())).isTrue();
	}

	private WebGroundedCitation citation(String answer, int start, int end) {
		return new WebGroundedCitation(
			"출처",
			URI.create("https://example.com/source"),
			answer.substring(start, end),
			BigDecimal.ONE,
			start,
			end
		);
	}
}
