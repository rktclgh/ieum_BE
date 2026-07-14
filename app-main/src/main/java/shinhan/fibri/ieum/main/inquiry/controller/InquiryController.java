package shinhan.fibri.ieum.main.inquiry.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.inquiry.dto.CreateInquiryRequest;
import shinhan.fibri.ieum.main.inquiry.dto.CreateInquiryResponse;
import shinhan.fibri.ieum.main.inquiry.dto.InquiryListResponse;
import shinhan.fibri.ieum.main.inquiry.dto.SuspendedUserInquiryRequest;
import shinhan.fibri.ieum.main.inquiry.exception.SuspendedUserInquiryRateLimitedException;
import shinhan.fibri.ieum.main.inquiry.service.InquiryService;
import shinhan.fibri.ieum.main.inquiry.service.SuspendedUserInquiryRateLimiter;
import shinhan.fibri.ieum.main.inquiry.service.SuspendedUserInquiryService;

@RestController
@RequestMapping("/api/v1/inquiries")
public class InquiryController {

	private final InquiryService inquiryService;
	private final SuspendedUserInquiryService suspendedUserInquiryService;
	private final SuspendedUserInquiryRateLimiter suspendedUserInquiryRateLimiter;

	public InquiryController(
		InquiryService inquiryService,
		SuspendedUserInquiryService suspendedUserInquiryService,
		SuspendedUserInquiryRateLimiter suspendedUserInquiryRateLimiter
	) {
		this.inquiryService = inquiryService;
		this.suspendedUserInquiryService = suspendedUserInquiryService;
		this.suspendedUserInquiryRateLimiter = suspendedUserInquiryRateLimiter;
	}

	@PostMapping
	public ResponseEntity<CreateInquiryResponse> create(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody CreateInquiryRequest request
	) {
		CreateInquiryResponse response = inquiryService.create(principal, request);
		return ResponseEntity.created(URI.create("/api/v1/inquiries/" + response.inquiryId()))
			.body(response);
	}

	@GetMapping("/me")
	public ResponseEntity<InquiryListResponse> listMine(@AuthenticationPrincipal AuthenticatedUser principal) {
		return ResponseEntity.ok(inquiryService.listMine(principal.userId()));
	}

	@PostMapping("/suspended-users")
	public ResponseEntity<Void> sendSuspendedUserInquiry(
		@Valid @RequestBody SuspendedUserInquiryRequest request,
		HttpServletRequest httpRequest
	) {
		if (!suspendedUserInquiryRateLimiter.tryAcquire(clientIp(httpRequest))) {
			throw new SuspendedUserInquiryRateLimitedException();
		}
		suspendedUserInquiryService.send(request);
		return ResponseEntity.ok().build();
	}

	private String clientIp(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",", 2)[0].trim();
		}
		return request.getRemoteAddr();
	}
}
