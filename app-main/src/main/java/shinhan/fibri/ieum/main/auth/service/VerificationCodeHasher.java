package shinhan.fibri.ieum.main.auth.service;

public interface VerificationCodeHasher {

	String hash(String email, String code);
}
