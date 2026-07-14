package shinhan.fibri.ieum.main.admin.inquiry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnswerInquiryRequest(
	@NotBlank @Size(max = 2000) String answer
) {

	public AnswerInquiryRequest {
		if (answer != null) {
			answer = answer.trim();
		}
	}
}
