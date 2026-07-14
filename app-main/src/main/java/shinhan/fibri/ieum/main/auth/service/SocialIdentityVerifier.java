package shinhan.fibri.ieum.main.auth.service;

import shinhan.fibri.ieum.main.auth.dto.SocialAuthRequest;

public interface SocialIdentityVerifier {

	VerifiedSocialIdentity verify(SocialAuthRequest request);
}
