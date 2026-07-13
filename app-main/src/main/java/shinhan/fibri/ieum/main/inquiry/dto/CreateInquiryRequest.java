package shinhan.fibri.ieum.main.inquiry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateInquiryRequest(
	@Size(max = 50) String title,
	@NotBlank @Size(max = 2000) String content
) {
	public CreateInquiryRequest {
		if (title != null) {
			title = title.trim();
		}
	}
}
