package shinhan.fibri.ieum.main.inquiry.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.mail.AfterCommitMailTaskScheduler;

@Component
public class AfterCommitInquiryAnswerMailPublisher implements InquiryAnswerMailEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(AfterCommitInquiryAnswerMailPublisher.class);

	private final AfterCommitMailTaskScheduler mailTaskScheduler;
	private final InquiryAnswerMailSender mailSender;

	public AfterCommitInquiryAnswerMailPublisher(
		AfterCommitMailTaskScheduler mailTaskScheduler,
		InquiryAnswerMailSender mailSender
	) {
		this.mailTaskScheduler = mailTaskScheduler;
		this.mailSender = mailSender;
	}

	@Override
	public void publish(InquiryAnswerMailEvent event) {
		mailTaskScheduler.executeAfterCommit("inquiry_answer_email", event.inquiryId(), () -> send(event));
	}

	private void send(InquiryAnswerMailEvent event) {
		try {
			mailSender.send(
				event.recipientEmail(),
				event.inquiryId(),
				event.title(),
				event.answer(),
				event.answeredAt(),
				event.locale()
			);
		} catch (RuntimeException exception) {
			log.error(
				"event=inquiry_answer_email_failed inquiryId={} failureType={}",
				event.inquiryId(),
				exception.getClass().getSimpleName()
			);
		}
	}
}
