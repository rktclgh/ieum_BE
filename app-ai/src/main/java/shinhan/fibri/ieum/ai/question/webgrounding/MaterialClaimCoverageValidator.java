package shinhan.fibri.ieum.ai.question.webgrounding;

import java.util.List;

final class MaterialClaimCoverageValidator {

	boolean coversEveryMaterialSentence(
		String answer,
		List<WebGroundedCitation> citations
	) {
		if (answer == null || citations == null) {
			return false;
		}
		boolean[] covered = new boolean[answer.length()];
		for (WebGroundedCitation citation : citations) {
			if (citation == null
				|| citation.startIndex() < 0
				|| citation.endIndex() > answer.length()
				|| citation.endIndex() <= citation.startIndex()) {
				return false;
			}
			for (int index = citation.startIndex(); index < citation.endIndex(); index++) {
				covered[index] = true;
			}
		}

		int sentenceStart = 0;
		for (int index = 0; index < answer.length();) {
			int codePoint = answer.codePointAt(index);
			int codePointLength = Character.charCount(codePoint);
			if (isSentenceBoundary(codePoint)) {
				if (!coversMaterialCharacters(answer, sentenceStart, index, covered)) {
					return false;
				}
				sentenceStart = index + codePointLength;
			}
			index += codePointLength;
		}
		return coversMaterialCharacters(answer, sentenceStart, answer.length(), covered);
	}

	private boolean coversMaterialCharacters(
		String answer,
		int start,
		int end,
		boolean[] covered
	) {
		for (int index = start; index < end;) {
			int codePoint = answer.codePointAt(index);
			int codePointLength = Character.charCount(codePoint);
			if (Character.isLetterOrDigit(codePoint)) {
				for (int offset = 0; offset < codePointLength; offset++) {
					if (!covered[index + offset]) {
						return false;
					}
				}
			}
			index += codePointLength;
		}
		return true;
	}

	private boolean isSentenceBoundary(int codePoint) {
		return codePoint == '\n'
			|| codePoint == '\r'
			|| codePoint == '.'
			|| codePoint == '!'
			|| codePoint == '?'
			|| codePoint == '。'
			|| codePoint == '！'
			|| codePoint == '？';
	}
}
