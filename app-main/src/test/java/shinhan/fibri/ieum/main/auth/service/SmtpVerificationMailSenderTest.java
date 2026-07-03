package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.i18n.LocaleContextHolder;
import shinhan.fibri.ieum.MainApplication;

import java.util.Locale;

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
			"noreply@example.com",
			messageSource()
		);

		LocaleContextHolder.setLocale(Locale.KOREAN);
		try {
			mailSender.sendSignupCode("user@example.com", "123456", 180);
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}

		var messageCaptor = forClass(SimpleMailMessage.class);
		verify(javaMailSender).send(messageCaptor.capture());
		SimpleMailMessage message = messageCaptor.getValue();
		assertThat(message.getFrom()).isEqualTo("noreply@example.com");
		assertThat(message.getTo()).containsExactly("user@example.com");
		assertThat(message.getSubject()).contains("이메일 인증");
		assertThat(message.getText()).contains("123456").contains("3분");
	}

	@Test
	void sendSignupCodeUsesLocaleMessageTemplate() {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		SmtpVerificationMailSender mailSender = new SmtpVerificationMailSender(
			javaMailSender,
			"noreply@example.com",
			messageSource()
		);

		LocaleContextHolder.setLocale(Locale.ENGLISH);
		try {
			mailSender.sendSignupCode("user@example.com", "123456", 180);
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}

		var messageCaptor = forClass(SimpleMailMessage.class);
		verify(javaMailSender).send(messageCaptor.capture());
		SimpleMailMessage message = messageCaptor.getValue();
		assertThat(message.getSubject()).isEqualTo("[Ieum] Email verification code");
		assertThat(message.getText()).contains("Verification code: 123456").contains("3 minutes");
	}

	@Test
	void sendSignupCodeRunsWithSpringAsyncEnabled() throws Exception {
		assertThat(MainApplication.class.isAnnotationPresent(EnableAsync.class)).isTrue();
		assertThat(SmtpVerificationMailSender.class
			.getMethod("sendSignupCode", String.class, String.class, int.class)
			.isAnnotationPresent(Async.class)).isTrue();
	}

	private StaticMessageSource messageSource() {
		StaticMessageSource messageSource = new StaticMessageSource();
		messageSource.addMessage(
			"auth.email.signup.subject",
			Locale.KOREAN,
			"[Ieum] 이메일 인증 코드"
		);
		messageSource.addMessage(
			"auth.email.signup.body",
			Locale.KOREAN,
			"이메일 인증 코드: {0}\n\n이 코드는 {1}분 동안 유효합니다."
		);
		messageSource.addMessage(
			"auth.email.signup.subject",
			Locale.ENGLISH,
			"[Ieum] Email verification code"
		);
		messageSource.addMessage(
			"auth.email.signup.body",
			Locale.ENGLISH,
			"Verification code: {0}\n\nThis code is valid for {1} minutes."
		);
		return messageSource;
	}
}
