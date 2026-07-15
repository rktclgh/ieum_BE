package shinhan.fibri.ieum.main.auth.session;

import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

public record ValidatedAuthSession(
	AuthenticatedUser principal,
	String sessionId
) {

	@Override
	public String toString() {
		return "ValidatedAuthSession[principal=%s, sessionId=<redacted>]".formatted(principal);
	}
}
