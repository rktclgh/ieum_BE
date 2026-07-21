package shinhan.fibri.ieum.main.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSender {

	private static final ClassPathResource LOGO_RESOURCE = new ClassPathResource("mail/ieum-logo.png");

	private final JavaMailSender mailSender;
	private final String fromAddress;

	public SmtpMailSender(
		JavaMailSender mailSender,
		@Value("${spring.mail.username}") String fromAddress
	) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	public void send(String recipient, String replyTo, RenderedEmail email) {
		MimeMessage message = mailSender.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
			helper.setFrom(fromAddress);
			helper.setTo(recipient);
			if (replyTo != null && !replyTo.isBlank()) {
				helper.setReplyTo(replyTo);
			}
			helper.setSubject(sanitizeHeader(email.subject()));
			helper.setText(email.plainText(), email.htmlText());
			helper.addInline(EmailTemplateRenderer.LOGO_CONTENT_ID, LOGO_RESOURCE, "image/png");
		} catch (MessagingException exception) {
			throw new MailPreparationException("Unable to prepare SMTP message", exception);
		}
		mailSender.send(message);
	}

	private String sanitizeHeader(String value) {
		return value.replace('\r', ' ').replace('\n', ' ');
	}
}
