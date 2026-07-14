package shinhan.fibri.ieum.main.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;

class SocialAuthRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void googleRequestRequiresIdToken() {
		SocialAuthRequest request = new SocialAuthRequest("google", "id-token", null, null, "nonce-1");

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void kakaoRequestRequiresCode() {
		SocialAuthRequest request = new SocialAuthRequest(
			"kakao",
			null,
			"authorization-code",
			"http://localhost:3000/oauth/kakao/callback",
			null
		);

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void requestRejectsUnsupportedProvider() {
		SocialAuthRequest request = new SocialAuthRequest("apple", "id-token", null, null, null);

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("provider"));
	}

	@Test
	void googleRequestRejectsBlankIdToken() {
		SocialAuthRequest request = new SocialAuthRequest("google", " ", null, null, null);

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("googleIdTokenPresent"));
	}

	@Test
	void kakaoRequestRejectsBlankCode() {
		SocialAuthRequest request = new SocialAuthRequest("kakao", null, " ", "http://localhost:3000/oauth/kakao/callback", null);

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("kakaoCodePresent"));
	}

	@Test
	void kakaoRequestRejectsBlankRedirectUri() {
		SocialAuthRequest request = new SocialAuthRequest("kakao", null, "authorization-code", " ", null);

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("kakaoRedirectUriPresent"));
	}

	@Test
	void responseSupportsExistingUserLoginShape() {
		SocialAuthResponse response = SocialAuthResponse.existingUser(1L, UserRole.user);

		assertThat(response.isNewUser()).isFalse();
		assertThat(response.userId()).isEqualTo(1L);
		assertThat(response.role()).isEqualTo(UserRole.user);
		assertThat(response.socialSignupToken()).isNull();
		assertThat(response.expiresInSeconds()).isNull();
	}

	@Test
	void responseSupportsNewUserSignupTokenShape() {
		SocialAuthResponse response = SocialAuthResponse.newUser("signup-token", 1800);

		assertThat(response.isNewUser()).isTrue();
		assertThat(response.userId()).isNull();
		assertThat(response.role()).isNull();
		assertThat(response.socialSignupToken()).isEqualTo("signup-token");
		assertThat(response.expiresInSeconds()).isEqualTo(1800);
	}
}
