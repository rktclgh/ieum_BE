package shinhan.fibri.ieum.main.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailSenderTest {

	@Test
	void sendsUtf8MultipartEmailWithPlainTextAndHtmlAlternatives() throws Exception {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		when(javaMailSender.createMimeMessage()).thenReturn(message);
		SmtpMailSender sender = new SmtpMailSender(javaMailSender, "noreply@example.com");

		sender.send(
			"user@example.com",
			"support@example.com",
			new RenderedEmail("[Ieum] 안내", "plain body", "<strong>html body</strong>")
		);

		verify(javaMailSender).send(message);
		assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.com");
		assertThat(message.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
		assertThat(message.getReplyTo()[0].toString()).isEqualTo("support@example.com");
		assertThat(message.getSubject()).isEqualTo("[Ieum] 안내");

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		message.writeTo(output);
		String source = output.toString(StandardCharsets.UTF_8);
		assertThat(source)
			.contains("text/plain")
			.contains("plain body")
			.contains("text/html")
			.contains("<strong>html body</strong>")
			.contains("Content-ID: <ieum-logo>")
			.contains("Content-Type: image/png");
	}
}
