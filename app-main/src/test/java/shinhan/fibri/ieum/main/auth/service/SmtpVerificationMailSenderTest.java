package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpVerificationMailSenderTest {

	@Test
	void sendSignupCodeSendsMailContainingCodeAndExpiry() {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		SmtpVerificationMailSender mailSender = new SmtpVerificationMailSender(
			javaMailSender,
			"noreply@example.com"
		);

		mailSender.sendSignupCode("user@example.com", "123456", 180);

		var messageCaptor = forClass(SimpleMailMessage.class);
		verify(javaMailSender).send(messageCaptor.capture());
		SimpleMailMessage message = messageCaptor.getValue();
		assertThat(message.getFrom()).isEqualTo("noreply@example.com");
		assertThat(message.getTo()).containsExactly("user@example.com");
		assertThat(message.getSubject()).contains("이메일 인증");
		assertThat(message.getText()).contains("123456").contains("3분");
	}
}
