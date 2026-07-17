package shinhan.fibri.ieum.main.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.mail.EmailTemplate;
import shinhan.fibri.ieum.main.mail.EmailTemplateRenderer;
import shinhan.fibri.ieum.main.mail.SmtpMailSender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class SmtpVerificationMailSender implements VerificationMailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpVerificationMailSender.class);

	private final SmtpMailSender mailSender;
	private final EmailTemplateRenderer templateRenderer;
	private final MessageSource messageSource;

	public SmtpVerificationMailSender(
		SmtpMailSender mailSender,
		EmailTemplateRenderer templateRenderer,
		MessageSource messageSource
	) {
		this.mailSender = mailSender;
		this.templateRenderer = templateRenderer;
		this.messageSource = messageSource;
	}

	@Override
	@Async("mailTaskExecutor")
	public CompletableFuture<Void> sendSignupCode(String email, String code, int expiresInSeconds) {
		var locale = LocaleContextHolder.getLocale();
		int expiresInMinutes = expiresInSeconds / 60;
		try {
			EmailTemplate template = new EmailTemplate(
				message("auth.email.signup.subject", null, locale),
				message("auth.email.signup.category", null, locale),
				message("auth.email.signup.headline", null, locale),
				message("auth.email.signup.intro", null, locale),
				List.of(
					new EmailTemplate.Detail(message("auth.email.signup.code.label", null, locale), code, true),
					new EmailTemplate.Detail(
						message("auth.email.signup.expiry.label", null, locale),
						message("auth.email.signup.expiry.value", new Object[]{expiresInMinutes}, locale),
						false
					)
				),
				message("auth.email.signup.notice", null, locale)
			);
			mailSender.send(email, null, templateRenderer.render(template, locale));
			log.info("SMTP signup mail sent: to={}", email);
			return CompletableFuture.completedFuture(null);
		} catch (RuntimeException exception) {
			return CompletableFuture.failedFuture(exception);
		}
	}

	private String message(String key, Object[] args, java.util.Locale locale) {
		return messageSource.getMessage(key, args, locale);
	}
}
