package shinhan.fibri.ieum.main.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import shinhan.fibri.ieum.main.mail.EmailTemplateRenderer;
import shinhan.fibri.ieum.main.mail.RenderedEmail;
import shinhan.fibri.ieum.main.mail.SmtpMailSender;

class SmtpInquiryAnswerMailSenderTest {

	@Test
	void sendsTheAnswerToTheOriginalRequesterUsingTheSharedTemplate() {
		SmtpMailSender smtpMailSender = mock(SmtpMailSender.class);
		SmtpInquiryAnswerMailSender mailSender = new SmtpInquiryAnswerMailSender(
			smtpMailSender,
			new EmailTemplateRenderer(messageSource()),
			messageSource()
		);

		mailSender.send(
			"user@example.com",
			90L,
			"Unable to sign in",
			"We reset your access. Please try again.",
			OffsetDateTime.parse("2026-07-16T15:00:00+09:00"),
			Locale.ENGLISH
		);

		var emailCaptor = forClass(RenderedEmail.class);
		verify(smtpMailSender).send(eq("user@example.com"), isNull(), emailCaptor.capture());
		RenderedEmail email = emailCaptor.getValue();
		assertThat(email.subject()).isEqualTo("[Ieum] An inquiry answer has arrived: Unable to sign in");
		assertThat(email.plainText())
			.contains("Unable to sign in")
			.contains("We reset your access. Please try again.")
			.contains("90")
			.contains("2026-07-16T15:00+09:00");
		assertThat(email.htmlText()).contains("IEUM").contains("We reset your access. Please try again.");
	}

	private StaticMessageSource messageSource() {
		StaticMessageSource messageSource = new StaticMessageSource();
		messageSource.addMessage("mail.template.footer", Locale.ENGLISH, "This is an automated email.");
		messageSource.addMessage("inquiry.answer.subject", Locale.ENGLISH, "[Ieum] An inquiry answer has arrived: {0}");
		messageSource.addMessage("inquiry.answer.category", Locale.ENGLISH, "Inquiry answer");
		messageSource.addMessage("inquiry.answer.headline", Locale.ENGLISH, "An inquiry answer has arrived");
		messageSource.addMessage("inquiry.answer.intro", Locale.ENGLISH, "The IEUM support team has answered your inquiry.");
		messageSource.addMessage("inquiry.answer.title.label", Locale.ENGLISH, "Inquiry title");
		messageSource.addMessage("inquiry.answer.answer.label", Locale.ENGLISH, "Support answer");
		messageSource.addMessage("inquiry.answer.id.label", Locale.ENGLISH, "Inquiry ID");
		messageSource.addMessage("inquiry.answer.answered-at.label", Locale.ENGLISH, "Answered at");
		messageSource.addMessage("inquiry.answer.notice", Locale.ENGLISH, "You can review your inquiry in the IEUM app.");
		return messageSource;
	}
}
