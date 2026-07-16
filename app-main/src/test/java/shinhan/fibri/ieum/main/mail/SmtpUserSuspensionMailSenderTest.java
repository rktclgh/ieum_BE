package shinhan.fibri.ieum.main.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

class SmtpUserSuspensionMailSenderTest {

	@Test
	void includesTheReasonAndRoundedUpDurationDaysInTheUserLanguage() {
		SmtpMailSender smtpMailSender = mock(SmtpMailSender.class);
		SmtpUserSuspensionMailSender mailSender = new SmtpUserSuspensionMailSender(
			smtpMailSender,
			new EmailTemplateRenderer(messageSource()),
			messageSource()
		);
		OffsetDateTime startsAt = OffsetDateTime.parse("2026-07-16T15:00:00+09:00");
		UserSuspensionEvent event = new UserSuspensionEvent(
			10L,
			"user@example.com",
			"Repeated abusive messages",
			startsAt,
			startsAt.plusHours(25)
		);

		mailSender.send(event, Locale.ENGLISH);

		var emailCaptor = forClass(RenderedEmail.class);
		verify(smtpMailSender).send(eq("user@example.com"), isNull(), emailCaptor.capture());
		RenderedEmail email = emailCaptor.getValue();
		assertThat(email.subject()).isEqualTo("[Ieum] Your account has been suspended");
		assertThat(email.plainText())
			.contains("Repeated abusive messages")
			.contains("2 days");
	}

	@Test
	void marksPermanentSuspensionWithoutInventingADuration() {
		SmtpMailSender smtpMailSender = mock(SmtpMailSender.class);
		SmtpUserSuspensionMailSender mailSender = new SmtpUserSuspensionMailSender(
			smtpMailSender,
			new EmailTemplateRenderer(messageSource()),
			messageSource()
		);
		UserSuspensionEvent event = new UserSuspensionEvent(
			10L,
			"user@example.com",
			"운영 정책 위반",
			OffsetDateTime.parse("2026-07-16T15:00:00+09:00"),
			null
		);

		mailSender.send(event, Locale.KOREAN);

		var emailCaptor = forClass(RenderedEmail.class);
		verify(smtpMailSender).send(eq("user@example.com"), isNull(), emailCaptor.capture());
		assertThat(emailCaptor.getValue().plainText()).contains("영구 정지").doesNotContain("0일");
	}

	private StaticMessageSource messageSource() {
		StaticMessageSource messageSource = new StaticMessageSource();
		messageSource.addMessage("mail.template.footer", Locale.KOREAN, "본 메일은 발신 전용입니다.");
		messageSource.addMessage("mail.template.footer", Locale.ENGLISH, "This is an automated email.");
		messageSource.addMessage("user.suspension.subject", Locale.KOREAN, "[Ieum] 계정 이용이 정지되었습니다");
		messageSource.addMessage("user.suspension.subject", Locale.ENGLISH, "[Ieum] Your account has been suspended");
		messageSource.addMessage("user.suspension.category", Locale.KOREAN, "계정 안내");
		messageSource.addMessage("user.suspension.category", Locale.ENGLISH, "Account notice");
		messageSource.addMessage("user.suspension.headline", Locale.KOREAN, "계정 이용이 정지되었습니다");
		messageSource.addMessage("user.suspension.headline", Locale.ENGLISH, "Your account has been suspended");
		messageSource.addMessage("user.suspension.intro", Locale.KOREAN, "운영 정책에 따라 계정 이용이 제한되었습니다.");
		messageSource.addMessage("user.suspension.intro", Locale.ENGLISH, "Your account access has been restricted under our operating policy.");
		messageSource.addMessage("user.suspension.reason.label", Locale.KOREAN, "정지 사유");
		messageSource.addMessage("user.suspension.reason.label", Locale.ENGLISH, "Reason");
		messageSource.addMessage("user.suspension.duration.label", Locale.KOREAN, "정지 기간");
		messageSource.addMessage("user.suspension.duration.label", Locale.ENGLISH, "Suspension duration");
		messageSource.addMessage("user.suspension.duration.days", Locale.KOREAN, "{0}일");
		messageSource.addMessage("user.suspension.duration.days", Locale.ENGLISH, "{0} days");
		messageSource.addMessage("user.suspension.duration.permanent", Locale.KOREAN, "영구 정지");
		messageSource.addMessage("user.suspension.duration.permanent", Locale.ENGLISH, "Permanent suspension");
		messageSource.addMessage("user.suspension.notice", Locale.KOREAN, "문의가 필요하면 앱 내 문의를 이용해 주세요.");
		messageSource.addMessage("user.suspension.notice", Locale.ENGLISH, "Please use the in-app inquiry channel if you need help.");
		return messageSource;
	}
}
