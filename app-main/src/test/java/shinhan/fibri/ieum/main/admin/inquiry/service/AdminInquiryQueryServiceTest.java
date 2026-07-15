package shinhan.fibri.ieum.main.admin.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryListRequest;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.admin.user.dto.CursorPage;
import shinhan.fibri.ieum.main.admin.inquiry.repository.AdminInquiryQueryRepository;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;

class AdminInquiryQueryServiceTest {

	private final AdminInquiryQueryRepository repository = mock(AdminInquiryQueryRepository.class);
	private final AdminInquiryQueryService service = new AdminInquiryQueryService(repository);

	@Test
	void returnsDefaultSizedCursorPage() {
		AdminInquiryItem item = item(90L);
		when(repository.findAdminItems(InquiryStatus.pending, null, 21)).thenReturn(List.of(item));

		CursorPage<AdminInquiryItem> response = service.list(new AdminInquiryListRequest(InquiryStatus.pending, null, null));

		assertThat(response.items()).containsExactly(item);
		assertThat(response.nextCursor()).isNull();
		verify(repository).findAdminItems(InquiryStatus.pending, null, 21);
	}

	@Test
	void returnsNextCursorWhenMoreRowsExist() {
		AdminInquiryItem first = item(90L);
		AdminInquiryItem extra = item(89L);
		String cursor = AdminInquiryCursor.encode(91L);
		when(repository.findAdminItems(null, 91L, 2)).thenReturn(List.of(first, extra));

		CursorPage<AdminInquiryItem> response = service.list(new AdminInquiryListRequest(null, cursor, 1));

		assertThat(response.items()).containsExactly(first);
		assertThat(response.nextCursor()).isEqualTo(AdminInquiryCursor.encode(90L));
		verify(repository).findAdminItems(null, 91L, 2);
	}

	@Test
	void returnsCanonicalInquiryById() {
		AdminInquiryItem item = item(90L);
		when(repository.findAdminItemById(90L)).thenReturn(Optional.of(item));

		assertThat(service.get(90L)).isEqualTo(item);
		verify(repository).findAdminItemById(90L);
	}

	@Test
	void rejectsMissingCanonicalInquiry() {
		when(repository.findAdminItemById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(404L))
			.isInstanceOf(InquiryNotFoundException.class);
	}

	private AdminInquiryItem item(Long inquiryId) {
		return new AdminInquiryItem(
			inquiryId,
			42L,
			"user@example.com",
			"문의 제목",
			"문의 내용",
			InquiryStatus.pending,
			null,
			null,
			null,
			OffsetDateTime.parse("2026-07-13T10:00:00+09:00")
		);
	}
}
