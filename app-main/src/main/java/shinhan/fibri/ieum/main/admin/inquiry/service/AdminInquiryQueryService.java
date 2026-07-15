package shinhan.fibri.ieum.main.admin.inquiry.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryListRequest;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.admin.user.dto.CursorPage;
import shinhan.fibri.ieum.main.admin.inquiry.repository.AdminInquiryQueryRepository;

@Service
public class AdminInquiryQueryService {

	private static final int DEFAULT_SIZE = 20;

	private final AdminInquiryQueryRepository repository;

	public AdminInquiryQueryService(AdminInquiryQueryRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public CursorPage<AdminInquiryItem> list(AdminInquiryListRequest request) {
		int size = request.size() == null ? DEFAULT_SIZE : request.size();
		Long cursorId = AdminInquiryCursor.decode(request.cursor());
		List<AdminInquiryItem> rows = repository.findAdminItems(request.status(), cursorId, size + 1);
		boolean hasNext = rows.size() > size;
		List<AdminInquiryItem> items = rows.stream()
			.limit(size)
			.toList();
		String nextCursor = hasNext ? AdminInquiryCursor.encode(items.getLast().inquiryId()) : null;
		return new CursorPage<>(items, nextCursor);
	}

	@Transactional(readOnly = true)
	public AdminInquiryItem get(Long inquiryId) {
		return repository.findAdminItemById(inquiryId)
			.orElseThrow(InquiryNotFoundException::new);
	}
}
