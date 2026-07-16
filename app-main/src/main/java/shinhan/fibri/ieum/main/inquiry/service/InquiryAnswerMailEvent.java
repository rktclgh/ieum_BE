package shinhan.fibri.ieum.main.inquiry.service;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;

public record InquiryAnswerMailEvent(
	String recipientEmail,
	Long inquiryId,
	String title,
	String answer,
	OffsetDateTime answeredAt,
	Locale locale
) {

	public InquiryAnswerMailEvent {
		recipientEmail = Objects.requireNonNull(recipientEmail, "recipientEmail must not be null");
		inquiryId = Objects.requireNonNull(inquiryId, "inquiryId must not be null");
		title = Objects.requireNonNull(title, "title must not be null");
		answer = Objects.requireNonNull(answer, "answer must not be null");
		answeredAt = Objects.requireNonNull(answeredAt, "answeredAt must not be null");
		locale = Objects.requireNonNull(locale, "locale must not be null");
	}
}
