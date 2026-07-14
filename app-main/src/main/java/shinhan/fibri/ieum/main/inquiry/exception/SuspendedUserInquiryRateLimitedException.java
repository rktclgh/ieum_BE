package shinhan.fibri.ieum.main.inquiry.exception;

public class SuspendedUserInquiryRateLimitedException extends RuntimeException {

	public SuspendedUserInquiryRateLimitedException() {
		super("Too many suspended user inquiries");
	}
}
