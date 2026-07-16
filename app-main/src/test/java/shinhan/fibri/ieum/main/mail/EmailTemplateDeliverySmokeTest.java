package shinhan.fibri.ieum.main.mail;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import shinhan.fibri.ieum.main.auth.service.SmtpVerificationMailSender;
import shinhan.fibri.ieum.main.inquiry.service.SmtpAdminInquiryMailSender;
import shinhan.fibri.ieum.main.inquiry.service.SmtpInquiryAnswerMailSender;

@Tag("manual-smoke")
@EnabledIfEnvironmentVariable(named = "EMAIL_SMOKE_RECIPIENT", matches = ".+")
class EmailTemplateDeliverySmokeTest {

	@Test
	void sendsEachSharedEmailTemplateToTheConfiguredRecipient() {
		String recipient = requiredEnvironment("EMAIL_SMOKE_RECIPIENT");
		ResourceBundleMessageSource messages = messageSource();
		SmtpMailSender smtpMailSender = new SmtpMailSender(javaMailSender(), requiredEnvironment("SMTP_USERNAME"));
		EmailTemplateRenderer templateRenderer = new EmailTemplateRenderer(messages);

		LocaleContextHolder.setLocale(Locale.KOREAN);
		try {
			new SmtpVerificationMailSender(smtpMailSender, templateRenderer, messages)
				.sendSignupCode(recipient, "902701", 180)
				.join();
			new SmtpAdminInquiryMailSender(smtpMailSender, templateRenderer, recipient, messages)
				.sendToAdmin("smoke-reply@example.com", "[SMTP smoke] 정지 계정 문의", "공용 이메일 템플릿 발송 점검입니다.");
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}

		new SmtpInquiryAnswerMailSender(smtpMailSender, templateRenderer, messages).send(
			recipient,
			902701L,
			"[SMTP smoke] 문의 답변",
			"공용 이메일 템플릿 발송 점검입니다.",
			OffsetDateTime.now(),
			Locale.KOREAN
		);
		new SmtpUserSuspensionMailSender(smtpMailSender, templateRenderer, messages).send(
			new UserSuspensionEvent(
				902701L,
				recipient,
				"SMTP smoke test - no account action was performed",
				OffsetDateTime.now(),
				OffsetDateTime.now().plusDays(2),
				Locale.ENGLISH
			)
		);
	}

	private JavaMailSenderImpl javaMailSender() {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(requiredEnvironment("SMTP_HOST"));
		sender.setPort(Integer.parseInt(System.getenv().getOrDefault("SMTP_PORT", "587")));
		sender.setUsername(requiredEnvironment("SMTP_USERNAME"));
		sender.setPassword(requiredEnvironment("SMTP_PASSWORD"));
		Properties properties = sender.getJavaMailProperties();
		properties.put("mail.smtp.auth", "true");
		properties.put("mail.smtp.starttls.enable", "true");
		properties.put("mail.smtp.connectiontimeout", "5000");
		properties.put("mail.smtp.timeout", "5000");
		properties.put("mail.smtp.writetimeout", "5000");
		return sender;
	}

	private ResourceBundleMessageSource messageSource() {
		ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
		messageSource.setBasename("messages");
		messageSource.setDefaultEncoding("UTF-8");
		return messageSource;
	}

	private String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be set for the email smoke test");
		}
		return value;
	}
}
