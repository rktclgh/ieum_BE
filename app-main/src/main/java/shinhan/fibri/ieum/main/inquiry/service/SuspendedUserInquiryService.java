package shinhan.fibri.ieum.main.inquiry.service;

import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.inquiry.dto.SuspendedUserInquiryRequest;

@Service
public class SuspendedUserInquiryService {

	private final AdminInquiryMailSender mailSender;

	public SuspendedUserInquiryService(AdminInquiryMailSender mailSender) {
		this.mailSender = mailSender;
	}

	public void send(SuspendedUserInquiryRequest request) {
		mailSender.sendToAdmin(request.email(), request.title(), request.content());
	}
}
