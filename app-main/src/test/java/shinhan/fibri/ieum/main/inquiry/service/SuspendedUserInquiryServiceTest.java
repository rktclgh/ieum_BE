package shinhan.fibri.ieum.main.inquiry.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.inquiry.dto.SuspendedUserInquiryRequest;

class SuspendedUserInquiryServiceTest {

	private final AdminInquiryMailSender mailSender = mock(AdminInquiryMailSender.class);
	private final SuspendedUserInquiryService service = new SuspendedUserInquiryService(mailSender);

	@Test
	void sendsMailToAdminSynchronously() {
		service.send(new SuspendedUserInquiryRequest(" user@example.com ", " 문의 제목 ", "문의 내용"));

		verify(mailSender).sendToAdmin("user@example.com", "문의 제목", "문의 내용");
	}
}
