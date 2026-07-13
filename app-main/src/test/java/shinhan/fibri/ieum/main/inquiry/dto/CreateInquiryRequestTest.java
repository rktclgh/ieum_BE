package shinhan.fibri.ieum.main.inquiry.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class CreateInquiryRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void trimsTitleBeforeValidationAndPersistence() {
		CreateInquiryRequest request = new CreateInquiryRequest("  " + "가".repeat(48) + "  ", "문의 내용");

		assertThat(request.title()).isEqualTo("가".repeat(48));
	}

	@Test
	void allowsTitleWithFiftyEmojiCodePoints() {
		CreateInquiryRequest request = new CreateInquiryRequest("\uD83D\uDE00".repeat(50), "문의 내용");

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void rejectsTitleWithMoreThanFiftyCodePoints() {
		CreateInquiryRequest request = new CreateInquiryRequest("\uD83D\uDE00".repeat(51), "문의 내용");

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("title"));
	}
}
