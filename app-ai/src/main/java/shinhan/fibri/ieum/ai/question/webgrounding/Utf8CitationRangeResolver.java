package shinhan.fibri.ieum.ai.question.webgrounding;

import java.util.Objects;
import java.util.Optional;

final class Utf8CitationRangeResolver {

	private Utf8CitationRangeResolver() {
	}

	static Optional<Range> resolve(
		String partText,
		int startByteIndex,
		int endByteIndex,
		String segmentText
	) {
		Objects.requireNonNull(partText, "partText must not be null");
		if (segmentText == null || segmentText.isBlank()
			|| startByteIndex < 0 || endByteIndex <= startByteIndex) {
			return Optional.empty();
		}

		int startIndex = utf16IndexAtByteBoundary(partText, startByteIndex);
		int endIndex = utf16IndexAtByteBoundary(partText, endByteIndex);
		if (startIndex < 0 || endIndex <= startIndex) {
			return Optional.empty();
		}
		String excerpt = partText.substring(startIndex, endIndex);
		if (!excerpt.equals(segmentText)) {
			return Optional.empty();
		}
		return Optional.of(new Range(startIndex, endIndex, excerpt));
	}

	private static int utf16IndexAtByteBoundary(String value, int targetByteIndex) {
		int byteIndex = 0;
		for (int utf16Index = 0; utf16Index < value.length();) {
			if (byteIndex == targetByteIndex) {
				return utf16Index;
			}
			int codePoint = value.codePointAt(utf16Index);
			byteIndex += utf8Length(codePoint);
			if (byteIndex > targetByteIndex) {
				return -1;
			}
			utf16Index += Character.charCount(codePoint);
		}
		return byteIndex == targetByteIndex ? value.length() : -1;
	}

	private static int utf8Length(int codePoint) {
		if (codePoint <= 0x7f) {
			return 1;
		}
		if (codePoint <= 0x7ff) {
			return 2;
		}
		if (codePoint <= 0xffff) {
			return 3;
		}
		return 4;
	}

	record Range(int startIndex, int endIndex, String excerpt) {

		Range {
			if (startIndex < 0 || endIndex <= startIndex) {
				throw new IllegalArgumentException("citation range must be nonempty");
			}
			excerpt = Objects.requireNonNull(excerpt, "excerpt must not be null");
		}
	}
}
