package shinhan.fibri.ieum.main.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class SmtpVerificationMailSender implements VerificationMailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpVerificationMailSender.class);

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
	@Async("mailTaskExecutor")
	public CompletableFuture<Void> sendSignupCode(String email, String code, int expiresInSeconds) {
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
		try {
			mailSender.send(message);
			log.info("SMTP signup mail sent: to={}", email);
			return CompletableFuture.completedFuture(null);
		} catch (RuntimeException exception) {
			return CompletableFuture.failedFuture(exception);
		}
	}
}
