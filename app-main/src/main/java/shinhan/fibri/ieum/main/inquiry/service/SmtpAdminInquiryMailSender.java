package shinhan.fibri.ieum.main.inquiry.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.mail.EmailTemplate;
import shinhan.fibri.ieum.main.mail.EmailTemplateRenderer;
import shinhan.fibri.ieum.main.mail.SmtpMailSender;

import java.util.List;

@Component
public class SmtpAdminInquiryMailSender implements AdminInquiryMailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpAdminInquiryMailSender.class);

	private final SmtpMailSender mailSender;
	private final EmailTemplateRenderer templateRenderer;
	private final String adminEmail;
	private final MessageSource messageSource;

	public SmtpAdminInquiryMailSender(
		SmtpMailSender mailSender,
		EmailTemplateRenderer templateRenderer,
		@Value("${app.inquiry.admin-email}") String adminEmail,
		MessageSource messageSource
	) {
		this.mailSender = mailSender;
		this.templateRenderer = templateRenderer;
		this.adminEmail = adminEmail;
		this.messageSource = messageSource;
	}

	@Override
	public void sendToAdmin(String requesterEmail, String title, String content) {
		var locale = LocaleContextHolder.getLocale();
		EmailTemplate template = new EmailTemplate(
			message("inquiry.suspended.subject", new Object[]{title}, locale),
			message("inquiry.suspended.category", null, locale),
			message("inquiry.suspended.headline", null, locale),
			message("inquiry.suspended.intro", null, locale),
			List.of(
				new EmailTemplate.Detail(message("inquiry.suspended.requester.label", null, locale), requesterEmail, false),
				new EmailTemplate.Detail(message("inquiry.suspended.content.label", null, locale), content, true)
			),
			message("inquiry.suspended.notice", null, locale)
		);
		mailSender.send(adminEmail, requesterEmail, templateRenderer.render(template, locale));
		log.info("SMTP suspended user inquiry mail sent: requesterEmail={}", requesterEmail);
	}

	private String message(String key, Object[] args, java.util.Locale locale) {
		return messageSource.getMessage(key, args, locale);
	}
}
