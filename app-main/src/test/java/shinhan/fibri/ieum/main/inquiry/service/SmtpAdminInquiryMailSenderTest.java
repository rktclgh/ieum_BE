package shinhan.fibri.ieum.main.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;

class SmtpAdminInquiryMailSenderTest {

	@Test
	void sendsSuspendedInquiryToConfiguredAdminWithReplyToRequester() {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		SmtpAdminInquiryMailSender mailSender = new SmtpAdminInquiryMailSender(
			javaMailSender,
			"noreply@example.com",
			"admin@example.com",
			messageSource()
		);

		LocaleContextHolder.setLocale(Locale.KOREAN);
		try {
			mailSender.sendToAdmin("user@example.com", "제재 문의", "로그인이 안 됩니다.");
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}

		var messageCaptor = forClass(SimpleMailMessage.class);
		verify(javaMailSender).send(messageCaptor.capture());
		SimpleMailMessage message = messageCaptor.getValue();
		assertThat(message.getFrom()).isEqualTo("noreply@example.com");
		assertThat(message.getTo()).containsExactly("admin@example.com");
		assertThat(message.getReplyTo()).isEqualTo("user@example.com");
		assertThat(message.getSubject()).isEqualTo("[Ieum] 정지 계정 문의: 제재 문의");
		assertThat(message.getText()).contains("문의자 이메일: user@example.com").contains("로그인이 안 됩니다.");
	}

	@Test
	void sendToAdminThrowsWhenSmtpFails() {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		doThrow(new MailSendException("smtp down"))
			.when(javaMailSender)
			.send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
		SmtpAdminInquiryMailSender mailSender = new SmtpAdminInquiryMailSender(
			javaMailSender,
			"noreply@example.com",
			"admin@example.com",
			messageSource()
		);

		LocaleContextHolder.setLocale(Locale.KOREAN);
		try {
			assertThatThrownBy(() -> mailSender.sendToAdmin("user@example.com", "제재 문의", "내용"))
				.isInstanceOf(MailSendException.class);
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}
	}

	@Test
	void sendToAdminIsNotAsync() throws Exception {
		Async async = SmtpAdminInquiryMailSender.class
			.getMethod("sendToAdmin", String.class, String.class, String.class)
			.getAnnotation(Async.class);

		assertThat(async).isNull();
	}

	private StaticMessageSource messageSource() {
		StaticMessageSource messageSource = new StaticMessageSource();
		messageSource.addMessage(
			"inquiry.suspended.subject",
			Locale.KOREAN,
			"[Ieum] 정지 계정 문의: {0}"
		);
		messageSource.addMessage(
			"inquiry.suspended.body",
			Locale.KOREAN,
			"문의자 이메일: {0}\n\n{1}"
		);
		messageSource.addMessage(
			"inquiry.suspended.subject",
			Locale.ENGLISH,
			"[Ieum] Suspended account inquiry: {0}"
		);
		messageSource.addMessage(
			"inquiry.suspended.body",
			Locale.ENGLISH,
			"Requester email: {0}\n\n{1}"
		);
		return messageSource;
	}
}
