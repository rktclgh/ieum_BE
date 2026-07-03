package shinhan.fibri.ieum.main.auth.dto;

public record VerifyEmailVerificationResponse(
	String emailVerificationToken,
	int expiresInSeconds
) {
}
