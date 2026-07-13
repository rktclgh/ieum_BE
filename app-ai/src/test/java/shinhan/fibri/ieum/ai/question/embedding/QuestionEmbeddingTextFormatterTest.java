package shinhan.fibri.ieum.ai.question.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.analysis.QuestionInputSnapshot;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;

class QuestionEmbeddingTextFormatterTest {

	private final QuestionEmbeddingTextFormatter formatter = new QuestionEmbeddingTextFormatter();

	@Test
	void formatsOnlyTitleAndContentWithoutNormalizingMeaningfulInternalText() {
		QuestionInputSnapshot snapshot = new QuestionInputSnapshot(
			"버스  탑승 방법",
			"앞문으로 타고\n뒷문으로  내려요",
			new StoredLocationSnapshot(
				37.5665d,
				126.9780d,
				"서울특별시 중구 세종대로 110",
				"상세주소-제외",
				"레이블-제외"
			)
		);

		String text = formatter.format(snapshot);

		assertThat(text)
			.isEqualTo("title: 버스  탑승 방법 | text: 앞문으로 타고\n뒷문으로  내려요")
			.doesNotContain("서울특별시", "37.5665", "126.978", "상세주소-제외", "레이블-제외");
	}

	@Test
	void rejectsNullSnapshot() {
		assertThatNullPointerException()
			.isThrownBy(() -> formatter.format(null))
			.withMessage("snapshot must not be null");
	}
}
