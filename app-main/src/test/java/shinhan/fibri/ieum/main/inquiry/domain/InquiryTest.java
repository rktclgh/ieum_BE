package shinhan.fibri.ieum.main.inquiry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class InquiryTest {

	@Test
	void createsPendingInquiryWithoutAnswer() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");

		assertThat(inquiry.getUserId()).isEqualTo(42L);
		assertThat(inquiry.getTitle()).isEqualTo("문의 제목");
		assertThat(inquiry.getContent()).isEqualTo("문의 내용");
		assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.pending);
		assertThat(inquiry.getAnswer()).isNull();
		assertThat(inquiry.getAnsweredBy()).isNull();
		assertThat(inquiry.getAnsweredAt()).isNull();
	}

	@Test
	void keepsInquiryOwnerColumnImmutable() throws NoSuchFieldException {
		Field userId = Inquiry.class.getDeclaredField("userId");

		assertThat(userId.getAnnotation(Column.class).updatable()).isFalse();
	}

	@Test
	void delegatesCreationTimestampToTheDatabase() throws NoSuchFieldException {
		Field createdAt = Inquiry.class.getDeclaredField("createdAt");
		Column column = createdAt.getAnnotation(Column.class);

		assertThat(column.insertable()).isFalse();
		assertThat(column.updatable()).isFalse();
	}

	@Test
	void answersPendingInquiry() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");
		OffsetDateTime answeredAt = OffsetDateTime.parse("2026-07-13T10:00:00+09:00");

		inquiry.answer("관리자 답변", 1L, answeredAt);

		assertThat(inquiry.isAnswered()).isTrue();
		assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.answered);
		assertThat(inquiry.getAnswer()).isEqualTo("관리자 답변");
		assertThat(inquiry.getAnsweredBy()).isEqualTo(1L);
		assertThat(inquiry.getAnsweredAt()).isEqualTo(answeredAt);
	}

	@Test
	void rejectsAnsweringAlreadyAnsweredInquiry() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");
		inquiry.answer("관리자 답변", 1L, OffsetDateTime.parse("2026-07-13T10:00:00+09:00"));

		assertThatThrownBy(() -> inquiry.answer("다른 답변", 2L, OffsetDateTime.now()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("inquiry is already answered");
	}
}
