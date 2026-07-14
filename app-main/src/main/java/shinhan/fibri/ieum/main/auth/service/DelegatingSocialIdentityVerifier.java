package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.auth.dto.SocialAuthRequest;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialTokenException;

@Service
@RequiredArgsConstructor
public class DelegatingSocialIdentityVerifier implements SocialIdentityVerifier {

	private final GoogleSocialIdentityVerifier googleVerifier;
	private final KakaoSocialIdentityVerifier kakaoVerifier;

	@Override
	public VerifiedSocialIdentity verify(SocialAuthRequest request) {
		if ("google".equals(request.provider())) {
			return googleVerifier.verify(request.idToken(), request.nonce());
		}
		if ("kakao".equals(request.provider())) {
			return kakaoVerifier.verify(request.code(), request.redirectUri());
		}
		throw new InvalidSocialTokenException();
	}
}
