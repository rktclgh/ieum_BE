package shinhan.fibri.ieum.main.inquiry.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuspendedUserInquiryRequest(
	@NotBlank @Email @Size(max = 254) String email,
	@NotBlank @Size(max = 200) String title,
	@NotBlank @Size(max = 2000) String content
) {

	public SuspendedUserInquiryRequest {
		if (email != null) {
			email = email.trim();
		}
		if (title != null) {
			title = title.trim();
		}
	}
}
