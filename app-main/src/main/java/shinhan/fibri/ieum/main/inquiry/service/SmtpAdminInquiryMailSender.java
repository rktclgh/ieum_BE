package shinhan.fibri.ieum.main.inquiry.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpAdminInquiryMailSender implements AdminInquiryMailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpAdminInquiryMailSender.class);

	private final JavaMailSender mailSender;
	private final String fromAddress;
	private final String adminEmail;
	private final MessageSource messageSource;

	public SmtpAdminInquiryMailSender(
		JavaMailSender mailSender,
		@Value("${spring.mail.username}") String fromAddress,
		@Value("${app.inquiry.admin-email}") String adminEmail,
		MessageSource messageSource
	) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
		this.adminEmail = adminEmail;
		this.messageSource = messageSource;
	}

	@Override
	public void sendToAdmin(String requesterEmail, String title, String content) {
		var locale = LocaleContextHolder.getLocale();
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(adminEmail);
		message.setReplyTo(requesterEmail);
		message.setSubject(messageSource.getMessage("inquiry.suspended.subject", new Object[]{title}, locale));
		message.setText(messageSource.getMessage(
			"inquiry.suspended.body",
			new Object[]{requesterEmail, content},
			locale
		));
		mailSender.send(message);
		log.info("SMTP suspended user inquiry mail sent: requesterEmail={}", requesterEmail);
	}
}
