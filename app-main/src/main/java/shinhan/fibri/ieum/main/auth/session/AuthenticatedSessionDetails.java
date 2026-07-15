package shinhan.fibri.ieum.main.auth.session;

public record AuthenticatedSessionDetails(String sessionId) {

	public AuthenticatedSessionDetails {
		if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
			throw new IllegalArgumentException("sessionId must contain between 1 and 64 characters");
		}
	}

	@Override
	public String toString() {
		return "AuthenticatedSessionDetails[sessionId=<redacted>]";
	}
}
