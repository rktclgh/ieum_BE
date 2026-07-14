package shinhan.fibri.ieum.main.admin.inquiry.exception;

public class InquiryNotFoundException extends RuntimeException {

	public InquiryNotFoundException() {
		super("Inquiry not found");
	}
}
