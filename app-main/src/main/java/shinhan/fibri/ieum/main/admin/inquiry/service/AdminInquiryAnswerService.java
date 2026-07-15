package shinhan.fibri.ieum.main.admin.inquiry.service;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AnswerInquiryRequest;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryAlreadyAnsweredException;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.repository.InquiryRepository;

@Service
public class AdminInquiryAnswerService {

	private final InquiryRepository inquiryRepository;
	private final AdminAuditLogWriter auditLogWriter;

	public AdminInquiryAnswerService(InquiryRepository inquiryRepository, AdminAuditLogWriter auditLogWriter) {
		this.inquiryRepository = inquiryRepository;
		this.auditLogWriter = auditLogWriter;
	}

	@Transactional
	public void answer(AuthenticatedUser principal, Long inquiryId, AnswerInquiryRequest request) {
		Inquiry inquiry = inquiryRepository.findByIdForUpdate(inquiryId)
			.orElseThrow(InquiryNotFoundException::new);
		if (inquiry.isAnswered()) {
			throw new InquiryAlreadyAnsweredException();
		}
		inquiry.answer(request.answer(), principal.userId(), OffsetDateTime.now());
		auditLogWriter.append(
			principal.userId(),
			AdminAuditAction.INQUIRY_ANSWERED,
			"inquiry",
			inquiryId,
			java.util.Map.of("answerLength", request.answer().length())
		);
	}
}
