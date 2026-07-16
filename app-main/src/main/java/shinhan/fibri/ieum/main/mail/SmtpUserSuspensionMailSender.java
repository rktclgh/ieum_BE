package shinhan.fibri.ieum.main.mail;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
public class SmtpUserSuspensionMailSender implements UserSuspensionMailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpUserSuspensionMailSender.class);

	private final SmtpMailSender mailSender;
	private final EmailTemplateRenderer templateRenderer;
	private final MessageSource messageSource;

	public SmtpUserSuspensionMailSender(
		SmtpMailSender mailSender,
		EmailTemplateRenderer templateRenderer,
		MessageSource messageSource
	) {
		this.mailSender = mailSender;
		this.templateRenderer = templateRenderer;
		this.messageSource = messageSource;
	}

	@Override
	public void send(UserSuspensionEvent event, Locale locale) {
		Locale resolvedLocale = locale == null ? Locale.KOREAN : locale;
		String duration = suspensionDuration(event.startsAt(), event.endsAt(), resolvedLocale);
		EmailTemplate template = new EmailTemplate(
			message("user.suspension.subject", null, resolvedLocale),
			message("user.suspension.category", null, resolvedLocale),
			message("user.suspension.headline", null, resolvedLocale),
			message("user.suspension.intro", null, resolvedLocale),
			List.of(
				new EmailTemplate.Detail(message("user.suspension.reason.label", null, resolvedLocale), event.reason(), true),
				new EmailTemplate.Detail(message("user.suspension.duration.label", null, resolvedLocale), duration, false)
			),
			message("user.suspension.notice", null, resolvedLocale)
		);
		mailSender.send(event.email(), null, templateRenderer.render(template, resolvedLocale));
		log.info("Suspension email sent: userId={}", event.userId());
	}

	private String suspensionDuration(OffsetDateTime startsAt, OffsetDateTime endsAt, Locale locale) {
		if (endsAt == null) {
			return message("user.suspension.duration.permanent", null, locale);
		}
		long seconds = Duration.between(startsAt, endsAt).getSeconds();
		long days = seconds <= 0 ? 1L : Math.max(1L, Math.ceilDiv(seconds, 86_400L));
		return message("user.suspension.duration.days", new Object[]{days}, locale);
	}

	private String message(String key, Object[] args, Locale locale) {
		return messageSource.getMessage(key, args, locale);
	}
}
