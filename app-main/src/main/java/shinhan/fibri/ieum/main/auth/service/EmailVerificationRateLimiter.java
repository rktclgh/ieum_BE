package shinhan.fibri.ieum.main.auth.service;

public interface EmailVerificationRateLimiter {

	boolean tryConsumeSignupSend(String email);

	boolean tryConsumeSignupVerifyFailure(String email);

	void clearSignupVerifyFailures(String email);
}
