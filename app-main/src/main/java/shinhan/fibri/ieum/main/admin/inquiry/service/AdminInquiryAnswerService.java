package shinhan.fibri.ieum.main.admin.inquiry.service;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AnswerInquiryRequest;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryAlreadyAnsweredException;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.repository.InquiryRepository;

@Service
public class AdminInquiryAnswerService {

	private final InquiryRepository inquiryRepository;

	public AdminInquiryAnswerService(InquiryRepository inquiryRepository) {
		this.inquiryRepository = inquiryRepository;
	}

	@Transactional
	public void answer(AuthenticatedUser principal, Long inquiryId, AnswerInquiryRequest request) {
		Inquiry inquiry = inquiryRepository.findByIdForUpdate(inquiryId)
			.orElseThrow(InquiryNotFoundException::new);
		if (inquiry.isAnswered()) {
			throw new InquiryAlreadyAnsweredException();
		}
		inquiry.answer(request.answer(), principal.userId(), OffsetDateTime.now());
	}
}
