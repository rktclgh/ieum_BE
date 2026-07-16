package shinhan.fibri.ieum.main.admin.inquiry.service;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AnswerInquiryRequest;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryAlreadyAnsweredException;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.repository.InquiryRepository;
import shinhan.fibri.ieum.main.inquiry.service.InquiryAnswerMailEvent;
import shinhan.fibri.ieum.main.inquiry.service.InquiryAnswerMailEventPublisher;
import shinhan.fibri.ieum.main.mail.UserMailLocaleResolver;

@Service
public class AdminInquiryAnswerService {

	private static final Logger log = LoggerFactory.getLogger(AdminInquiryAnswerService.class);

	private final InquiryRepository inquiryRepository;
	private final UserRepository userRepository;
	private final UserMailLocaleResolver userMailLocaleResolver;
	private final InquiryAnswerMailEventPublisher inquiryAnswerMailEventPublisher;
	private final AdminAuditLogWriter auditLogWriter;

	public AdminInquiryAnswerService(
		InquiryRepository inquiryRepository,
		UserRepository userRepository,
		UserMailLocaleResolver userMailLocaleResolver,
		InquiryAnswerMailEventPublisher inquiryAnswerMailEventPublisher,
		AdminAuditLogWriter auditLogWriter
	) {
		this.inquiryRepository = inquiryRepository;
		this.userRepository = userRepository;
		this.userMailLocaleResolver = userMailLocaleResolver;
		this.inquiryAnswerMailEventPublisher = inquiryAnswerMailEventPublisher;
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
		userRepository.findByIdAndDeletedAtIsNull(inquiry.getUserId()).ifPresentOrElse(
			requester -> inquiryAnswerMailEventPublisher.publish(new InquiryAnswerMailEvent(
				requester.getEmail(),
				inquiryId,
				inquiry.getTitle(),
				inquiry.getAnswer(),
				inquiry.getAnsweredAt(),
				userMailLocaleResolver.resolve(inquiry.getUserId())
			)),
			() -> log.warn("Inquiry answer email skipped because requester was not found: inquiryId={}", inquiryId)
		);
	}
}
