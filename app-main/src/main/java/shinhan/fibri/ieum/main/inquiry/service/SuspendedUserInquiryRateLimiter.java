package shinhan.fibri.ieum.main.inquiry.service;

public interface SuspendedUserInquiryRateLimiter {

	boolean tryAcquire(String clientIp);
}
