package shinhan.fibri.ieum.main.inquiry.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.mail.EmailTemplate;
import shinhan.fibri.ieum.main.mail.EmailTemplateRenderer;
import shinhan.fibri.ieum.main.mail.SmtpMailSender;

@Component
public class SmtpInquiryAnswerMailSender implements InquiryAnswerMailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpInquiryAnswerMailSender.class);

	private final SmtpMailSender mailSender;
	private final EmailTemplateRenderer templateRenderer;
	private final MessageSource messageSource;

	public SmtpInquiryAnswerMailSender(
		SmtpMailSender mailSender,
		EmailTemplateRenderer templateRenderer,
		MessageSource messageSource
	) {
		this.mailSender = mailSender;
		this.templateRenderer = templateRenderer;
		this.messageSource = messageSource;
	}

	@Override
	public void send(
		String recipientEmail,
		Long inquiryId,
		String title,
		String answer,
		OffsetDateTime answeredAt,
		Locale locale
	) {
		Locale resolvedLocale = locale == null ? Locale.KOREAN : locale;
		EmailTemplate template = new EmailTemplate(
			message("inquiry.answer.subject", new Object[]{title}, resolvedLocale),
			message("inquiry.answer.category", null, resolvedLocale),
			message("inquiry.answer.headline", null, resolvedLocale),
			message("inquiry.answer.intro", null, resolvedLocale),
			List.of(
				new EmailTemplate.Detail(message("inquiry.answer.title.label", null, resolvedLocale), title, false),
				new EmailTemplate.Detail(message("inquiry.answer.answer.label", null, resolvedLocale), answer, true),
				new EmailTemplate.Detail(message("inquiry.answer.id.label", null, resolvedLocale), inquiryId.toString(), false),
				new EmailTemplate.Detail(message("inquiry.answer.answered-at.label", null, resolvedLocale), answeredAt.toString(), false)
			),
			message("inquiry.answer.notice", null, resolvedLocale)
		);
		mailSender.send(recipientEmail, null, templateRenderer.render(template, resolvedLocale));
		log.info("Inquiry answer email sent: inquiryId={}", inquiryId);
	}

	private String message(String key, Object[] args, Locale locale) {
		return messageSource.getMessage(key, args, locale);
	}
}
