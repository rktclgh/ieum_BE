package shinhan.fibri.ieum.main.inquiry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
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
		assertThat(inquiry.getCreatedAt()).isNotNull();
	}

	@Test
	void keepsInquiryOwnerColumnImmutable() throws NoSuchFieldException {
		Field userId = Inquiry.class.getDeclaredField("userId");

		assertThat(userId.getAnnotation(Column.class).updatable()).isFalse();
	}
}
