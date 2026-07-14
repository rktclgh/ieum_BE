package shinhan.fibri.ieum.main.inquiry.service;

public interface AdminInquiryMailSender {

	void sendToAdmin(String requesterEmail, String title, String content);
}
