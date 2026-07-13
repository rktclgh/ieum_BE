package shinhan.fibri.ieum.main.inquiry.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.dto.CreateInquiryRequest;
import shinhan.fibri.ieum.main.inquiry.dto.CreateInquiryResponse;
import shinhan.fibri.ieum.main.inquiry.dto.InquiryItem;
import shinhan.fibri.ieum.main.inquiry.dto.InquiryListResponse;
import shinhan.fibri.ieum.main.inquiry.repository.InquiryRepository;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
public class InquiryService {

	private final UserRepository userRepository;
	private final InquiryRepository inquiryRepository;

	public InquiryService(UserRepository userRepository, InquiryRepository inquiryRepository) {
		this.userRepository = userRepository;
		this.inquiryRepository = inquiryRepository;
	}

	@Transactional
	public CreateInquiryResponse create(AuthenticatedUser principal, CreateInquiryRequest request) {
		userRepository.findByIdAndDeletedAtIsNull(principal.userId())
			.orElseThrow(UserNotFoundException::new);

		Inquiry inquiry = inquiryRepository.save(Inquiry.create(
			principal.userId(),
			normalizeTitle(request.title(), request.content()),
			request.content()
		));
		return new CreateInquiryResponse(inquiry.getId());
	}

	@Transactional(readOnly = true)
	public InquiryListResponse listMine(Long userId) {
		return new InquiryListResponse(inquiryRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
			.stream()
			.map(InquiryItem::from)
			.toList());
	}

	private String normalizeTitle(String title, String content) {
		if (title != null && !title.isBlank()) {
			return title.trim();
		}
		String normalizedContent = content.trim().replaceAll("\\s+", " ");
		return normalizedContent.substring(0, Math.min(normalizedContent.length(), 50));
	}
}
