package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import shinhan.fibri.ieum.main.mail.EmailTemplateRenderer;
import shinhan.fibri.ieum.main.mail.RenderedEmail;
import shinhan.fibri.ieum.main.mail.SmtpMailSender;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class SmtpVerificationMailSenderTest {

	@Test
	void sendSignupCodeSendsMailContainingCodeAndExpiry() {
		SmtpMailSender smtpMailSender = mock(SmtpMailSender.class);
		SmtpVerificationMailSender mailSender = mailSender(smtpMailSender);

		LocaleContextHolder.setLocale(Locale.KOREAN);
		try {
			mailSender.sendSignupCode("user@example.com", "123456", 180).join();
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}

		var emailCaptor = forClass(RenderedEmail.class);
		verify(smtpMailSender).send(
			org.mockito.ArgumentMatchers.eq("user@example.com"),
			org.mockito.ArgumentMatchers.isNull(),
			emailCaptor.capture()
		);
		RenderedEmail email = emailCaptor.getValue();
		assertThat(email.subject()).contains("이메일 인증");
		assertThat(email.plainText()).contains("123456").contains("3분");
	}

	@Test
	void sendSignupCodeUsesLocaleMessageTemplate() {
		SmtpMailSender smtpMailSender = mock(SmtpMailSender.class);
		SmtpVerificationMailSender mailSender = mailSender(smtpMailSender);

		LocaleContextHolder.setLocale(Locale.ENGLISH);
		try {
			mailSender.sendSignupCode("user@example.com", "123456", 180).join();
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}

		var emailCaptor = forClass(RenderedEmail.class);
		verify(smtpMailSender).send(
			org.mockito.ArgumentMatchers.eq("user@example.com"),
			org.mockito.ArgumentMatchers.isNull(),
			emailCaptor.capture()
		);
		RenderedEmail email = emailCaptor.getValue();
		assertThat(email.subject()).isEqualTo("[Ieum] Email verification code");
		assertThat(email.plainText()).contains("Verification code: 123456").contains("3 minutes");
	}

	@Test
	void sendSignupCodeReturnsFailedFutureWithoutDuplicatedWarningWhenMailSendFails(CapturedOutput output) {
		SmtpMailSender smtpMailSender = mock(SmtpMailSender.class);
		doThrow(new RuntimeException("smtp down"))
			.when(smtpMailSender)
			.send(
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.isNull(),
				org.mockito.ArgumentMatchers.any(RenderedEmail.class)
			);
		SmtpVerificationMailSender mailSender = mailSender(smtpMailSender);

		LocaleContextHolder.setLocale(Locale.KOREAN);
		try {
			CompletableFuture<Void> result = mailSender.sendSignupCode("user@example.com", "123456", 180);

			assertThat(result).isCompletedExceptionally();
			assertThat(output).doesNotContain("SMTP signup mail send error");
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}
	}

	@Test
	void sendSignupCodeRunsOnMailTaskExecutor() throws Exception {
		Async async = SmtpVerificationMailSender.class
			.getMethod("sendSignupCode", String.class, String.class, int.class)
			.getAnnotation(Async.class);

		assertThat(async).isNotNull();
		assertThat(async.value()).isEqualTo("mailTaskExecutor");
	}

	private StaticMessageSource messageSource() {
		StaticMessageSource messageSource = new StaticMessageSource();
		messageSource.addMessage("mail.template.footer", Locale.KOREAN, "본 메일은 발신 전용입니다.");
		messageSource.addMessage("mail.template.footer", Locale.ENGLISH, "This is an automated email.");
		messageSource.addMessage(
			"auth.email.signup.subject",
			Locale.KOREAN,
			"[Ieum] 이메일 인증 코드"
		);
		messageSource.addMessage("auth.email.signup.category", Locale.KOREAN, "계정 안내");
		messageSource.addMessage("auth.email.signup.headline", Locale.KOREAN, "이메일 인증을 완료해 주세요");
		messageSource.addMessage("auth.email.signup.intro", Locale.KOREAN, "아래 인증 코드를 입력해 주세요.");
		messageSource.addMessage("auth.email.signup.code.label", Locale.KOREAN, "인증 코드");
		messageSource.addMessage("auth.email.signup.expiry.label", Locale.KOREAN, "유효 시간");
		messageSource.addMessage("auth.email.signup.expiry.value", Locale.KOREAN, "{0}분");
		messageSource.addMessage("auth.email.signup.notice", Locale.KOREAN, "본인이 요청하지 않았다면 이 메일을 무시해 주세요.");
		messageSource.addMessage(
			"auth.email.signup.subject",
			Locale.ENGLISH,
			"[Ieum] Email verification code"
		);
		messageSource.addMessage("auth.email.signup.category", Locale.ENGLISH, "Account notice");
		messageSource.addMessage("auth.email.signup.headline", Locale.ENGLISH, "Complete your email verification");
		messageSource.addMessage("auth.email.signup.intro", Locale.ENGLISH, "Enter the verification code below.");
		messageSource.addMessage("auth.email.signup.code.label", Locale.ENGLISH, "Verification code");
		messageSource.addMessage("auth.email.signup.expiry.label", Locale.ENGLISH, "Valid for");
		messageSource.addMessage("auth.email.signup.expiry.value", Locale.ENGLISH, "{0} minutes");
		messageSource.addMessage("auth.email.signup.notice", Locale.ENGLISH, "Ignore this email if you did not request it.");
		return messageSource;
	}

	private SmtpVerificationMailSender mailSender(SmtpMailSender smtpMailSender) {
		StaticMessageSource messageSource = messageSource();
		return new SmtpVerificationMailSender(
			smtpMailSender,
			new EmailTemplateRenderer(messageSource),
			messageSource
		);
	}
}
