package shinhan.fibri.ieum.main.auth.service;

public interface VerificationMailSender {

	void sendSignupCode(String email, String code, int expiresInSeconds);
}
