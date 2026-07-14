package shinhan.fibri.ieum.main.auth.service;

import shinhan.fibri.ieum.common.auth.domain.AuthProvider;

public record SocialSignupIdentity(
	AuthProvider provider,
	String providerUid,
	String email,
	boolean emailVerified
) {
}
