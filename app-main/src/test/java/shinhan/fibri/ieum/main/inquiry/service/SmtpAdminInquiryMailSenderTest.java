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
import org.springframework.scheduling.annotation.Async;
import shinhan.fibri.ieum.main.mail.EmailTemplateRenderer;
import shinhan.fibri.ieum.main.mail.RenderedEmail;
import shinhan.fibri.ieum.main.mail.SmtpMailSender;

class SmtpAdminInquiryMailSenderTest {

	@Test
	void sendsSuspendedInquiryToConfiguredAdminWithReplyToRequester() {
		SmtpMailSender smtpMailSender = mock(SmtpMailSender.class);
		SmtpAdminInquiryMailSender mailSender = mailSender(smtpMailSender);

		LocaleContextHolder.setLocale(Locale.KOREAN);
		try {
			mailSender.sendToAdmin("user@example.com", "제재 문의", "로그인이 안 됩니다.");
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}

		var emailCaptor = forClass(RenderedEmail.class);
		verify(smtpMailSender).send(
			org.mockito.ArgumentMatchers.eq("admin@example.com"),
			org.mockito.ArgumentMatchers.eq("user@example.com"),
			emailCaptor.capture()
		);
		RenderedEmail email = emailCaptor.getValue();
		assertThat(email.subject()).isEqualTo("[Ieum] 정지 계정 문의: 제재 문의");
		assertThat(email.plainText()).contains("문의자 이메일: user@example.com").contains("로그인이 안 됩니다.");
	}

	@Test
	void sendToAdminThrowsWhenSmtpFails() {
		SmtpMailSender smtpMailSender = mock(SmtpMailSender.class);
		doThrow(new MailSendException("smtp down"))
			.when(smtpMailSender)
			.send(
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any(RenderedEmail.class)
			);
		SmtpAdminInquiryMailSender mailSender = mailSender(smtpMailSender);

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
		messageSource.addMessage("mail.template.footer", Locale.KOREAN, "본 메일은 발신 전용입니다.");
		messageSource.addMessage("mail.template.footer", Locale.ENGLISH, "This is an automated email.");
		messageSource.addMessage(
			"inquiry.suspended.subject",
			Locale.KOREAN,
			"[Ieum] 정지 계정 문의: {0}"
		);
		messageSource.addMessage("inquiry.suspended.category", Locale.KOREAN, "정지 계정 문의");
		messageSource.addMessage("inquiry.suspended.headline", Locale.KOREAN, "정지 계정 문의가 접수되었습니다");
		messageSource.addMessage("inquiry.suspended.intro", Locale.KOREAN, "아래 문의 내용을 확인해 주세요.");
		messageSource.addMessage("inquiry.suspended.requester.label", Locale.KOREAN, "문의자 이메일");
		messageSource.addMessage("inquiry.suspended.content.label", Locale.KOREAN, "문의 내용");
		messageSource.addMessage("inquiry.suspended.notice", Locale.KOREAN, "답변은 관리자 문의 화면에서 등록해 주세요.");
		messageSource.addMessage(
			"inquiry.suspended.subject",
			Locale.ENGLISH,
			"[Ieum] Suspended account inquiry: {0}"
		);
		messageSource.addMessage("inquiry.suspended.category", Locale.ENGLISH, "Suspended account inquiry");
		messageSource.addMessage("inquiry.suspended.headline", Locale.ENGLISH, "A suspended account inquiry has arrived");
		messageSource.addMessage("inquiry.suspended.intro", Locale.ENGLISH, "Review the inquiry details below.");
		messageSource.addMessage("inquiry.suspended.requester.label", Locale.ENGLISH, "Requester email");
		messageSource.addMessage("inquiry.suspended.content.label", Locale.ENGLISH, "Inquiry content");
		messageSource.addMessage("inquiry.suspended.notice", Locale.ENGLISH, "Reply from the administrator inquiry screen.");
		return messageSource;
	}

	private SmtpAdminInquiryMailSender mailSender(SmtpMailSender smtpMailSender) {
		StaticMessageSource messageSource = messageSource();
		return new SmtpAdminInquiryMailSender(
			smtpMailSender,
			new EmailTemplateRenderer(messageSource),
			"admin@example.com",
			messageSource
		);
	}
}
