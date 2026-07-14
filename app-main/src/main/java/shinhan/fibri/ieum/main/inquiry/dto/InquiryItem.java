package shinhan.fibri.ieum.main.inquiry.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;

public record InquiryItem(
	Long inquiryId,
	String title,
	String content,
	InquiryStatus status,
	String answer,
	OffsetDateTime answeredAt,
	OffsetDateTime createdAt
) {

	public static InquiryItem from(Inquiry inquiry) {
		return new InquiryItem(
			inquiry.getId(),
			inquiry.getTitle(),
			inquiry.getContent(),
			inquiry.getStatus(),
			inquiry.getAnswer(),
			inquiry.getAnsweredAt(),
			inquiry.getCreatedAt()
		);
	}
}
