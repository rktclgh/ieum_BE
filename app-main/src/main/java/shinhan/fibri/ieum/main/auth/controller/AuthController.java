package shinhan.fibri.ieum.main.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.service.EmailVerificationService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final EmailVerificationService emailVerificationService;

	@PostMapping("/email/send")
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
}
