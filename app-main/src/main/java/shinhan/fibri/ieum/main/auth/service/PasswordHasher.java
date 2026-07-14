package shinhan.fibri.ieum.main.auth.service;

public interface PasswordHasher {

	String hash(String rawPassword);

	boolean matches(String rawPassword, String encodedPassword);
}
