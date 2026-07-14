package shinhan.fibri.ieum.main.admin.inquiry.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;

public record AdminInquiryListRequest(
	InquiryStatus status,
	String cursor,
	@Min(1)
	@Max(50)
	Integer size
) {
}
