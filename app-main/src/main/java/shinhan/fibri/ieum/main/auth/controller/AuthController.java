package shinhan.fibri.ieum.main.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.auth.dto.CheckEmailDuplicateRequest;
import shinhan.fibri.ieum.main.auth.dto.CheckNicknameDuplicateRequest;
import shinhan.fibri.ieum.main.auth.dto.DuplicateCheckResponse;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.dto.SignupRequest;
import shinhan.fibri.ieum.main.auth.dto.SignupResponse;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.service.EmailVerificationService;
import shinhan.fibri.ieum.main.auth.service.SignupService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final EmailVerificationService emailVerificationService;
	private final SignupService signupService;

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
}
