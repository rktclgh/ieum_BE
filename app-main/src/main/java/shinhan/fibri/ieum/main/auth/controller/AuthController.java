package shinhan.fibri.ieum.main.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.auth.dto.CheckEmailDuplicateRequest;
import shinhan.fibri.ieum.main.auth.dto.CheckNicknameDuplicateRequest;
import shinhan.fibri.ieum.main.auth.dto.DuplicateCheckResponse;
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
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final EmailVerificationService emailVerificationService;
	private final SignupService signupService;
	private final LoginService loginService;
	private final RefreshService refreshService;
	private final LogoutService logoutService;
	private final SocialAuthService socialAuthService;
	private final AuthCookieWriter authCookieWriter;

	@PostMapping("/email/send-code")
	public ResponseEntity<SendEmailVerificationResponse> sendEmailVerificationCode(
		@Valid @RequestBody SendEmailVerificationRequest request
	) {
		SendEmailVerificationResponse response = emailVerificationService.sendSignupCode(request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("/email/verify")
	public ResponseEntity<VerifyEmailVerificationResponse> verifyEmailVerificationCode(
		@Valid @RequestBody VerifyEmailVerificationRequest request
	) {
		VerifyEmailVerificationResponse response = emailVerificationService.verifySignupCode(request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("/email/check-duplicate")
	public ResponseEntity<DuplicateCheckResponse> checkEmailDuplicate(
		@Valid @RequestBody CheckEmailDuplicateRequest request
	) {
		return ResponseEntity.ok(new DuplicateCheckResponse(signupService.isEmailAvailable(request.email())));
	}

	@PostMapping("/nickname/check-duplicate")
	public ResponseEntity<DuplicateCheckResponse> checkNicknameDuplicate(
		@Valid @RequestBody CheckNicknameDuplicateRequest request
	) {
		return ResponseEntity.ok(new DuplicateCheckResponse(signupService.isNicknameAvailable(request.nickname())));
	}

	@PostMapping("/signup")
	public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		SignupResponse response = signupService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(
		@Valid @RequestBody LoginRequest request,
		HttpServletResponse response
	) {
		LoginResult result = loginService.login(request);
		authCookieWriter.writeLoginCookies(
			response,
			result.accessToken(),
			result.refreshToken(),
			result.csrfToken()
		);
		return ResponseEntity.ok(result.response());
	}

	@PostMapping("/social")
	public ResponseEntity<SocialAuthResponse> socialAuth(
		@Valid @RequestBody SocialAuthRequest request,
		HttpServletResponse response
	) {
		SocialAuthResult result = socialAuthService.start(request);
		if (!result.response().isNewUser()) {
			authCookieWriter.writeLoginCookies(
				response,
				result.accessToken(),
				result.refreshToken(),
				result.csrfToken()
			);
		}
		return ResponseEntity.ok(result.response());
	}

	@PostMapping("/social/signup")
	public ResponseEntity<SocialSignupResponse> socialSignup(
		@Valid @RequestBody SocialSignupRequest request,
		HttpServletResponse response
	) {
		SocialSignupResult result = socialAuthService.signup(request);
		authCookieWriter.writeLoginCookies(
			response,
			result.accessToken(),
			result.refreshToken(),
			result.csrfToken()
		);
		return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
	}

	@PostMapping("/refresh")
	public ResponseEntity<RefreshResponse> refresh(
		@CookieValue("refresh_token") String refreshToken,
		HttpServletResponse response
	) {
		RefreshResult result = refreshService.refresh(refreshToken);
		authCookieWriter.writeLoginCookies(
			response,
			result.accessToken(),
			result.refreshToken(),
			result.csrfToken()
		);
		return ResponseEntity.ok(result.response());
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
		@CookieValue(value = "refresh_token", required = false) String refreshToken,
		HttpServletResponse response
	) {
		if (refreshToken != null) {
			logoutService.logout(refreshToken);
		}
		authCookieWriter.writeExpiredAuthCookies(response);
		return ResponseEntity.noContent().build();
	}
}
