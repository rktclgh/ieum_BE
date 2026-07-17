package shinhan.fibri.ieum.main.admin.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AnswerInquiryRequest;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryAlreadyAnsweredException;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;
import shinhan.fibri.ieum.main.inquiry.repository.InquiryRepository;
import shinhan.fibri.ieum.main.inquiry.service.InquiryAnswerMailEvent;
import shinhan.fibri.ieum.main.inquiry.service.InquiryAnswerMailEventPublisher;
import shinhan.fibri.ieum.main.mail.UserMailLocaleResolver;

class AdminInquiryAnswerServiceTest {

	private final InquiryRepository inquiryRepository = mock(InquiryRepository.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final UserMailLocaleResolver userMailLocaleResolver = mock(UserMailLocaleResolver.class);
	private final InquiryAnswerMailEventPublisher inquiryAnswerMailEventPublisher = mock(InquiryAnswerMailEventPublisher.class);
	private final AdminAuditLogWriter auditLogWriter = mock(AdminAuditLogWriter.class);
	private final AdminInquiryAnswerService service = new AdminInquiryAnswerService(
		inquiryRepository,
		userRepository,
		userMailLocaleResolver,
		inquiryAnswerMailEventPublisher,
		auditLogWriter
	);

	@Test
	void answersPendingInquiryWithAdminIdAndTimestamp() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");
		User requester = mock(User.class);
		when(inquiryRepository.findByIdForUpdate(90L)).thenReturn(Optional.of(inquiry));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(requester));
		when(requester.getEmail()).thenReturn("user@example.com");
		when(userMailLocaleResolver.resolve(42L)).thenReturn(Locale.ENGLISH);

		service.answer(admin(), 90L, new AnswerInquiryRequest("답변 내용"));

		assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.answered);
		assertThat(inquiry.getAnswer()).isEqualTo("답변 내용");
		assertThat(inquiry.getAnsweredBy()).isEqualTo(1L);
		assertThat(inquiry.getAnsweredAt()).isNotNull();
		assertThat(inquiry.getAnsweredAt()).isBeforeOrEqualTo(OffsetDateTime.now());
		verify(auditLogWriter).append(
			1L,
			AdminAuditAction.INQUIRY_ANSWERED,
			"inquiry",
			90L,
			java.util.Map.of("answerLength", 5)
		);
		var eventCaptor = forClass(InquiryAnswerMailEvent.class);
		verify(inquiryAnswerMailEventPublisher).publish(eventCaptor.capture());
		InquiryAnswerMailEvent event = eventCaptor.getValue();
		assertThat(event.recipientEmail()).isEqualTo("user@example.com");
		assertThat(event.inquiryId()).isEqualTo(90L);
		assertThat(event.title()).isEqualTo("문의 제목");
		assertThat(event.answer()).isEqualTo("답변 내용");
		assertThat(event.answeredAt()).isEqualTo(inquiry.getAnsweredAt());
		assertThat(event.locale()).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void keepsAnswerWhenRequesterNoLongerExistsButSkipsMailEvent() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");
		when(inquiryRepository.findByIdForUpdate(90L)).thenReturn(Optional.of(inquiry));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		service.answer(admin(), 90L, new AnswerInquiryRequest("답변 내용"));

		assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.answered);
		verify(inquiryAnswerMailEventPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void rejectsAlreadyAnsweredInquiry() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");
		inquiry.answer("기존 답변", 1L, OffsetDateTime.parse("2026-07-13T10:00:00+09:00"));
		when(inquiryRepository.findByIdForUpdate(90L)).thenReturn(Optional.of(inquiry));

		assertThatThrownBy(() -> service.answer(admin(), 90L, new AnswerInquiryRequest("새 답변")))
			.isInstanceOf(InquiryAlreadyAnsweredException.class);
		verify(auditLogWriter, never()).append(
			org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()
		);
		verify(inquiryAnswerMailEventPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void rejectsMissingInquiry() {
		when(inquiryRepository.findByIdForUpdate(90L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.answer(admin(), 90L, new AnswerInquiryRequest("답변")))
			.isInstanceOf(InquiryNotFoundException.class);
		verify(auditLogWriter, never()).append(
			org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()
		);
		verify(inquiryAnswerMailEventPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
	}

	private AuthenticatedUser admin() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}
}
