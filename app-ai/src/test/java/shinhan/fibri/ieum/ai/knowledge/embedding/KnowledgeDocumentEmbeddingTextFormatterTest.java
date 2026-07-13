package shinhan.fibri.ieum.ai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class KnowledgeDocumentEmbeddingTextFormatterTest {

	private final KnowledgeDocumentEmbeddingTextFormatter formatter =
		new KnowledgeDocumentEmbeddingTextFormatter();

	@Test
	void formatsNonblankTitleAndContentExactly() {
		assertThat(formatter.format("버스 이용 방법", "앞문으로 타고 뒷문으로 내려요"))
			.isEqualTo("title: 버스 이용 방법 | text: 앞문으로 타고 뒷문으로 내려요");
	}

	@Test
	void usesNoneWhenTitleIsNull() {
		assertThat(formatter.format(null, "본문"))
			.isEqualTo("title: none | text: 본문");
	}

	@Test
	void usesNoneWhenTitleIsBlank() {
		assertThat(formatter.format(" \t\n", "본문"))
			.isEqualTo("title: none | text: 본문");
	}

	@Test
	void preservesInternalWhitespaceAndNewlines() {
		assertThat(formatter.format("버스  이용\n방법", "앞문으로  타고\n뒷문으로\t내려요"))
			.isEqualTo("title: 버스  이용\n방법 | text: 앞문으로  타고\n뒷문으로\t내려요");
	}

	@Test
	void rejectsNullOrBlankContentWithStableMessage() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> formatter.format("제목", null))
			.withMessage("content must not be blank");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> formatter.format("제목", ""))
			.withMessage("content must not be blank");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> formatter.format("제목", " \t\n"))
			.withMessage("content must not be blank");
	}
}
