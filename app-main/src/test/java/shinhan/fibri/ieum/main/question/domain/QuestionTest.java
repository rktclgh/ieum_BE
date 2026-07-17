package shinhan.fibri.ieum.main.question.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuestionTest {

	@Test
	void editReplacesTitleAndContent() {
		Question question = Question.create(100L, 42L, "old title", "old content");

		question.edit("new title", "new content");

		assertThat(question.getTitle()).isEqualTo("new title");
		assertThat(question.getContent()).isEqualTo("new content");
	}

	@Test
	void editRejectsNullTitle() {
		Question question = Question.create(100L, 42L, "old title", "old content");

		assertThatThrownBy(() -> question.edit(null, "new content"))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void editRejectsNullContent() {
		Question question = Question.create(100L, 42L, "old title", "old content");

		assertThatThrownBy(() -> question.edit("new title", null))
			.isInstanceOf(NullPointerException.class);
	}
}
