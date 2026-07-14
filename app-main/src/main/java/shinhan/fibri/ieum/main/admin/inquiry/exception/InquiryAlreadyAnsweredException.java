package shinhan.fibri.ieum.main.admin.inquiry.exception;

public class InquiryAlreadyAnsweredException extends RuntimeException {

	public InquiryAlreadyAnsweredException() {
		super("Inquiry is already answered");
	}
}
