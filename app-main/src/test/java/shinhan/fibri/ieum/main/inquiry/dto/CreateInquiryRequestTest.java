package shinhan.fibri.ieum.main.inquiry.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CreateInquiryRequestTest {

	@Test
	void trimsTitleBeforeValidationAndPersistence() {
		CreateInquiryRequest request = new CreateInquiryRequest("  " + "가".repeat(48) + "  ", "문의 내용");

		assertThat(request.title()).isEqualTo("가".repeat(48));
	}
}
