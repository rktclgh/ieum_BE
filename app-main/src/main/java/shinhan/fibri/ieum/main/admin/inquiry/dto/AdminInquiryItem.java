package shinhan.fibri.ieum.main.admin.inquiry.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;

public record AdminInquiryItem(
	Long inquiryId,
	Long userId,
	String userEmail,
	String title,
	String content,
	InquiryStatus status,
	String answer,
	Long answeredBy,
	OffsetDateTime answeredAt,
	OffsetDateTime createdAt
) {
}
