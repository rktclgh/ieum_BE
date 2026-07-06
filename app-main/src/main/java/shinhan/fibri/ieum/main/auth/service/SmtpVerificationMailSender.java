package shinhan.fibri.ieum.main.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpVerificationMailSender implements VerificationMailSender {

	private final JavaMailSender mailSender;
	private final String fromAddress;
	private final MessageSource messageSource;

	public SmtpVerificationMailSender(
		JavaMailSender mailSender,
		@Value("${spring.mail.username}") String fromAddress,
		MessageSource messageSource
	) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
		this.messageSource = messageSource;
	}

	@Override
	public void sendSignupCode(String email, String code, int expiresInSeconds) {
		var locale = LocaleContextHolder.getLocale();
		int expiresInMinutes = expiresInSeconds / 60;
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(email);
		message.setSubject(messageSource.getMessage("auth.email.signup.subject", null, locale));
		message.setText(messageSource.getMessage(
			"auth.email.signup.body",
			new Object[]{code, expiresInMinutes},
			locale
		));
		mailSender.send(message);
	}
}
