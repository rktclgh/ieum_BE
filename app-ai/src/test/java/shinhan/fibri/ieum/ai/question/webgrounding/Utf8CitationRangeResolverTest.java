package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Utf8CitationRangeResolverTest {

	@Test
	void convertsKoreanUtf8ByteOffsetsToJavaUtf16Indices() {
		String answer = "서울 버스는 앞문으로 탑니다.";
		int start = bytes("서울 ");
		int end = bytes("서울 버스");

		assertThat(Utf8CitationRangeResolver.resolve(answer, start, end, "버스"))
			.contains(new Utf8CitationRangeResolver.Range(3, 5, "버스"));
	}

	@Test
	void convertsOffsetsAfterAnEmojiWithoutSplittingItsSurrogatePair() {
		String answer = "🙂 대중교통을 이용하세요.";
		int start = bytes("🙂 ");
		int end = bytes("🙂 대중");

		assertThat(Utf8CitationRangeResolver.resolve(answer, start, end, "대중"))
			.contains(new Utf8CitationRangeResolver.Range(3, 5, "대중"));
	}

	@Test
	void rejectsOffsetsInsideAUtf8CodePoint() {
		String answer = "서울";

		assertThat(Utf8CitationRangeResolver.resolve(answer, 1, bytes("서울"), "서울"))
			.isEmpty();
	}

	@Test
	void rejectsOutOfBoundsBlankOrMismatchedSegments() {
		String answer = "서울 버스";

		assertThat(Utf8CitationRangeResolver.resolve(answer, -1, 3, "서")).isEmpty();
		assertThat(Utf8CitationRangeResolver.resolve(answer, 0, bytes(answer) + 1, answer)).isEmpty();
		assertThat(Utf8CitationRangeResolver.resolve(answer, 0, bytes("서울"), "부산")).isEmpty();
		assertThat(Utf8CitationRangeResolver.resolve(answer, 0, bytes("서울"), "  ")).isEmpty();
	}

	private int bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}
}
