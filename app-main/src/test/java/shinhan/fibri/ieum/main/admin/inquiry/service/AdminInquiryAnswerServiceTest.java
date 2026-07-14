package shinhan.fibri.ieum.main.admin.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AnswerInquiryRequest;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryAlreadyAnsweredException;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;
import shinhan.fibri.ieum.main.inquiry.repository.InquiryRepository;

class AdminInquiryAnswerServiceTest {

	private final InquiryRepository inquiryRepository = mock(InquiryRepository.class);
	private final AdminInquiryAnswerService service = new AdminInquiryAnswerService(inquiryRepository);

	@Test
	void answersPendingInquiryWithAdminIdAndTimestamp() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");
		when(inquiryRepository.findByIdForUpdate(90L)).thenReturn(Optional.of(inquiry));

		service.answer(admin(), 90L, new AnswerInquiryRequest("답변 내용"));

		assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.answered);
		assertThat(inquiry.getAnswer()).isEqualTo("답변 내용");
		assertThat(inquiry.getAnsweredBy()).isEqualTo(1L);
		assertThat(inquiry.getAnsweredAt()).isNotNull();
		assertThat(inquiry.getAnsweredAt()).isBeforeOrEqualTo(OffsetDateTime.now());
	}

	@Test
	void rejectsAlreadyAnsweredInquiry() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");
		inquiry.answer("기존 답변", 1L, OffsetDateTime.parse("2026-07-13T10:00:00+09:00"));
		when(inquiryRepository.findByIdForUpdate(90L)).thenReturn(Optional.of(inquiry));

		assertThatThrownBy(() -> service.answer(admin(), 90L, new AnswerInquiryRequest("새 답변")))
			.isInstanceOf(InquiryAlreadyAnsweredException.class);
	}

	@Test
	void rejectsMissingInquiry() {
		when(inquiryRepository.findByIdForUpdate(90L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.answer(admin(), 90L, new AnswerInquiryRequest("답변")))
			.isInstanceOf(InquiryNotFoundException.class);
	}

	private AuthenticatedUser admin() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}
}
