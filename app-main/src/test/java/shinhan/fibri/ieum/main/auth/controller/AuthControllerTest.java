package shinhan.fibri.ieum.main.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.main.auth.dto.LoginRequest;
import shinhan.fibri.ieum.main.auth.dto.LoginResponse;
import shinhan.fibri.ieum.main.auth.dto.RefreshResponse;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.dto.SignupRequest;
import shinhan.fibri.ieum.main.auth.dto.SignupResponse;
import shinhan.fibri.ieum.main.auth.dto.SocialAuthRequest;
import shinhan.fibri.ieum.main.auth.dto.SocialAuthResponse;
import shinhan.fibri.ieum.main.auth.dto.SocialSignupRequest;
import shinhan.fibri.ieum.main.auth.dto.SocialSignupResponse;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailCodeRateLimitedException;
import shinhan.fibri.ieum.main.auth.exception.EmailDeliveryFailedException;
import shinhan.fibri.ieum.main.auth.exception.EmailTakenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationCodeException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationTokenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialSignupTokenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialTokenException;
import shinhan.fibri.ieum.main.auth.exception.NicknameTakenException;
import shinhan.fibri.ieum.main.auth.service.EmailVerificationService;
import shinhan.fibri.ieum.main.auth.service.LoginResult;
import shinhan.fibri.ieum.main.auth.service.LoginService;
import shinhan.fibri.ieum.main.auth.service.LogoutService;
import shinhan.fibri.ieum.main.auth.service.RefreshResult;
import shinhan.fibri.ieum.main.auth.service.RefreshService;
import shinhan.fibri.ieum.main.auth.service.SignupService;
import shinhan.fibri.ieum.main.auth.service.SocialAuthResult;
import shinhan.fibri.ieum.main.auth.service.SocialAuthService;
import shinhan.fibri.ieum.main.auth.service.SocialSignupResult;
import shinhan.fibri.ieum.main.auth.session.AuthCookieWriter;
import shinhan.fibri.ieum.main.auth.session.AuthSessionProperties;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private EmailVerificationService emailVerificationService;

	@Autowired
	private SignupService signupService;

	@Autowired
	private LoginService loginService;

	@Autowired
	private RefreshService refreshService;

	@Autowired
	private LogoutService logoutService;

	@Autowired
	private SocialAuthService socialAuthService;

	@Test
	void sendEmailVerificationCodeReturnsExpirySeconds() throws Exception {
		when(emailVerificationService.sendSignupCode(any(SendEmailVerificationRequest.class)))
			.thenReturn(new SendEmailVerificationResponse(180));

		mockMvc.perform(post("/api/v1/auth/email/send-code")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expiresInSeconds", is(180)));
	}

	@Test
	void sendEmailVerificationCodeDoesNotSupportLegacySendPath() throws Exception {
		when(emailVerificationService.sendSignupCode(any(SendEmailVerificationRequest.class)))
			.thenReturn(new SendEmailVerificationResponse(180));

		mockMvc.perform(post("/api/v1/auth/email/send")
				.contentType(MediaType.APPLICATION_JSON)
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token")
				.content("""
					{
					  "email": "USER@example.com"
					}
					"""))
			.andExpect(status().isNotFound());
	}

	@Test
	void sendEmailVerificationCodeReturnsConflictWhenEmailIsTaken() throws Exception {
		doThrow(new EmailTakenException())
			.when(emailVerificationService)
			.sendSignupCode(any(SendEmailVerificationRequest.class));

		mockMvc.perform(post("/api/v1/auth/email/send-code")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("EMAIL_TAKEN")))
			.andExpect(jsonPath("$.message", is("Email is already taken")));
	}

	@Test
	void sendEmailVerificationCodeReturnsTooManyRequestsWhenRateLimited() throws Exception {
		doThrow(new EmailCodeRateLimitedException())
			.when(emailVerificationService)
			.sendSignupCode(any(SendEmailVerificationRequest.class));

		mockMvc.perform(post("/api/v1/auth/email/send-code")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com"
					}
					"""))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code", is("EMAIL_CODE_RATE_LIMITED")))
			.andExpect(jsonPath("$.message", is("Email verification code request rate limit exceeded")));
	}

	@Test
	void sendEmailVerificationCodeReturnsBadGatewayWhenMailDeliveryFails() throws Exception {
		doThrow(new EmailDeliveryFailedException())
			.when(emailVerificationService)
			.sendSignupCode(any(SendEmailVerificationRequest.class));

		mockMvc.perform(post("/api/v1/auth/email/send-code")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com"
					}
					"""))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code", is("EMAIL_DELIVERY_FAILED")))
			.andExpect(jsonPath("$.message", is("Failed to send email verification code")));
	}

	@Test
	void checkEmailDuplicateReturnsAvailability() throws Exception {
		when(signupService.isEmailAvailable("USER@example.com")).thenReturn(true);

		mockMvc.perform(post("/api/v1/auth/email/check-duplicate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available", is(true)));
	}

	@Test
	void checkNicknameDuplicateReturnsAvailability() throws Exception {
		when(signupService.isNicknameAvailable("nickname")).thenReturn(false);

		mockMvc.perform(post("/api/v1/auth/nickname/check-duplicate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "nickname": "nickname"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available", is(false)));
	}

	@Test
	void verifyEmailVerificationCodeReturnsVerificationToken() throws Exception {
		when(emailVerificationService.verifySignupCode(any(VerifyEmailVerificationRequest.class)))
			.thenReturn(new VerifyEmailVerificationResponse("verification-token", 1800));

		mockMvc.perform(post("/api/v1/auth/email/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "code": "123456"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.emailVerificationToken", is("verification-token")))
			.andExpect(jsonPath("$.expiresInSeconds", is(1800)));
	}

	@Test
	void verifyEmailVerificationCodeReturnsBadRequestWhenCodeIsInvalid() throws Exception {
		doThrow(new InvalidEmailVerificationCodeException())
			.when(emailVerificationService)
			.verifySignupCode(any(VerifyEmailVerificationRequest.class));

		mockMvc.perform(post("/api/v1/auth/email/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "code": "000000"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_EMAIL_VERIFICATION_CODE")))
			.andExpect(jsonPath("$.message", is("Invalid email verification code")));
	}

	@Test
	void signupReturnsCreatedUserId() throws Exception {
		when(signupService.signup(any(SignupRequest.class)))
			.thenReturn(new SignupResponse(42L));

		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.userId", is(42)));
	}

	@Test
	void loginReturnsUserSummaryAndAuthCookies() throws Exception {
		when(loginService.login(any(LoginRequest.class)))
			.thenReturn(new LoginResult(
				new LoginResponse(42L, UserRole.user, false),
				"access-token",
				"refresh-token",
				"csrf-token"
			));

		mockMvc.perform(post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId", is(42)))
			.andExpect(jsonPath("$.role", is("user")))
			.andExpect(jsonPath("$.passwordResetRequired", is(false)))
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anySatisfy(cookie -> assertThat(cookie).contains("access_token=access-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=refresh-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("csrf_token=csrf-token")));
	}

	@Test
	void socialAuthExistingUserReturnsSummaryAndAuthCookies() throws Exception {
		when(socialAuthService.start(any(SocialAuthRequest.class)))
			.thenReturn(new SocialAuthResult(
				SocialAuthResponse.existingUser(42L, UserRole.user),
				"access-token",
				"refresh-token",
				"csrf-token"
			));

		mockMvc.perform(post("/api/v1/auth/social")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "google",
					  "idToken": "id-token",
					  "nonce": "nonce-1"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isNewUser", is(false)))
			.andExpect(jsonPath("$.userId", is(42)))
			.andExpect(jsonPath("$.role", is("user")))
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anySatisfy(cookie -> assertThat(cookie).contains("access_token=access-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=refresh-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("csrf_token=csrf-token")));
	}

	@Test
	void socialAuthNewUserReturnsSignupTokenWithoutAuthCookies() throws Exception {
		when(socialAuthService.start(any(SocialAuthRequest.class)))
			.thenReturn(new SocialAuthResult(
				SocialAuthResponse.newUser("signup-token", 1800),
				null,
				null,
				null
			));

		mockMvc.perform(post("/api/v1/auth/social")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "google",
					  "idToken": "id-token"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isNewUser", is(true)))
			.andExpect(jsonPath("$.socialSignupToken", is("signup-token")))
			.andExpect(jsonPath("$.expiresInSeconds", is(1800)))
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty());
	}

	@Test
	void socialSignupReturnsCreatedUserSummaryAndAuthCookies() throws Exception {
		when(socialAuthService.signup(any(SocialSignupRequest.class)))
			.thenReturn(new SocialSignupResult(
				new SocialSignupResponse(42L, UserRole.user),
				"access-token",
				"refresh-token",
				"csrf-token"
			));

		mockMvc.perform(post("/api/v1/auth/social/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "socialSignupToken": "signup-token",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.userId", is(42)))
			.andExpect(jsonPath("$.role", is("user")))
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anySatisfy(cookie -> assertThat(cookie).contains("access_token=access-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=refresh-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("csrf_token=csrf-token")));
	}

	@Test
	void socialAuthReturnsUnauthorizedWhenProviderTokenIsInvalid() throws Exception {
		doThrow(new InvalidSocialTokenException())
			.when(socialAuthService)
			.start(any(SocialAuthRequest.class));

		mockMvc.perform(post("/api/v1/auth/social")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "google",
					  "idToken": "bad-token"
					}
					"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("INVALID_SOCIAL_TOKEN")));
	}

	@Test
	void socialSignupReturnsBadRequestWhenSignupTokenIsInvalid() throws Exception {
		doThrow(new InvalidSocialSignupTokenException())
			.when(socialAuthService)
			.signup(any(SocialSignupRequest.class));

		mockMvc.perform(post("/api/v1/auth/social/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "socialSignupToken": "expired-token",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_SOCIAL_SIGNUP_TOKEN")));
	}

	@Test
	void refreshReturnsUserSummaryAndRotatedAuthCookies() throws Exception {
		when(refreshService.refresh("refresh-token"))
			.thenReturn(new RefreshResult(
				new RefreshResponse(42L, UserRole.user),
				"new-access-token",
				"new-refresh-token",
				"new-csrf-token"
			));

		mockMvc.perform(post("/api/v1/auth/refresh")
				.cookie(new MockCookie("refresh_token", "refresh-token"))
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId", is(42)))
			.andExpect(jsonPath("$.role", is("user")))
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anySatisfy(cookie -> assertThat(cookie).contains("access_token=new-access-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=new-refresh-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("csrf_token=new-csrf-token")));
	}

	@Test
	void refreshReturnsUnauthorizedWhenRefreshCookieIsMissing() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("INVALID_REFRESH_TOKEN")))
			.andExpect(jsonPath("$.message", is("Invalid refresh token")));
	}

	@Test
	void logoutRevokesSessionAndExpiresAuthCookies() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout")
				.cookie(new MockCookie("refresh_token", "refresh-token"))
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token"))
			.andExpect(status().isNoContent())
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anySatisfy(cookie -> assertThat(cookie)
					.contains("access_token=")
					.contains("Max-Age=0"))
				.anySatisfy(cookie -> assertThat(cookie)
					.contains("refresh_token=")
					.contains("Max-Age=0"))
				.anySatisfy(cookie -> assertThat(cookie)
					.contains("csrf_token=")
					.contains("Max-Age=0")));

		verify(logoutService).logout("refresh-token");
	}

	@Test
	void logoutIsNoContentWhenRefreshCookieIsMissing() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout")
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token"))
			.andExpect(status().isNoContent())
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anySatisfy(cookie -> assertThat(cookie).contains("access_token=").contains("Max-Age=0"))
				.anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=").contains("Max-Age=0"))
				.anySatisfy(cookie -> assertThat(cookie).contains("csrf_token=").contains("Max-Age=0")));
	}

	@Test
	void signupReturnsBadRequestWhenVerificationTokenIsInvalid() throws Exception {
		doThrow(new InvalidEmailVerificationTokenException())
			.when(signupService)
			.signup(any(SignupRequest.class));

		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "invalid-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_EMAIL_VERIFICATION_TOKEN")))
			.andExpect(jsonPath("$.message", is("Invalid email verification token")));
	}

	@Test
	void signupReturnsConflictWhenEmailIsTaken() throws Exception {
		doThrow(new EmailTakenException())
			.when(signupService)
			.signup(any(SignupRequest.class));

		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("EMAIL_TAKEN")))
			.andExpect(jsonPath("$.message", is("Email is already taken")));
	}

	@Test
	void signupReturnsConflictWhenNicknameIsTaken() throws Exception {
		doThrow(new NicknameTakenException())
			.when(signupService)
			.signup(any(SignupRequest.class));

		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("NICKNAME_TAKEN")))
			.andExpect(jsonPath("$.message", is("Nickname is already taken")));
	}

	@Test
	void signupReturnsBadRequestWhenPasswordIsTooShort() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Pass@1",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("password")))
			.andExpect(jsonPath("$.fieldErrors[0].message").exists());
	}

	@Test
	void signupReturnsBadRequestWhenPasswordHasNoSpecialCharacter() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Password123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void signupReturnsBadRequestWhenPasswordExceedsBcryptByteLimit() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "가가가가가가가가가가가가가가가가가가가가가가가가가@",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("password")));
	}

	@Test
	void signupReturnsBadRequestWhenBirthDateIsFuture() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "3000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("birthDate")));
	}

	@Test
	void signupReturnsBadRequestWhenGenderIsInvalid() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "unknown",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("gender")));
	}

	@Test
	void signupReturnsBadRequestWhenNationalityFormatIsInvalid() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "kr",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("nationality")));
	}

	@Test
	void signupReturnsBadRequestWhenLanguageIsInvalid() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "de",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("language")));
	}

	@Test
	void verifyEmailVerificationCodeReturnsBadRequestWhenCodeFormatIsInvalid() throws Exception {
		mockMvc.perform(post("/api/v1/auth/email/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "code": "12345a"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("code")));
	}

	@Test
	void signupReturnsBadRequestWhenNicknameIsShorterThanTwoCharacters() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "n",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void signupReturnsBadRequestWhenNicknameContainsProfanity() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "fuckmaster",
					  "birthDate": "2000-01-01",
					  "gender": "female",
					  "nationality": "KR",
					  "language": "ko",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		EmailVerificationService emailVerificationService() {
			return mock(EmailVerificationService.class);
		}

		@Bean
		@Primary
		SignupService signupService() {
			return mock(SignupService.class);
		}

		@Bean
		@Primary
		LoginService loginService() {
			return mock(LoginService.class);
		}

		@Bean
		@Primary
		RefreshService refreshService() {
			return mock(RefreshService.class);
		}

		@Bean
		@Primary
		LogoutService logoutService() {
			return mock(LogoutService.class);
		}

		@Bean
		@Primary
		SocialAuthService socialAuthService() {
			return mock(SocialAuthService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		@Primary
		AuthCookieWriter authCookieWriter() {
			return new AuthCookieWriter(new AuthSessionProperties(true, "Lax", "", 1800, 1209600));
		}
	}
}
