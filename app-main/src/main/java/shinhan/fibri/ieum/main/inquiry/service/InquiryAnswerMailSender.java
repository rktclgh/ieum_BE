package shinhan.fibri.ieum.main.inquiry.service;

import java.time.OffsetDateTime;
import java.util.Locale;

public interface InquiryAnswerMailSender {

	void send(
		String recipientEmail,
		Long inquiryId,
		String title,
		String answer,
		OffsetDateTime answeredAt,
		Locale locale
	);
}
