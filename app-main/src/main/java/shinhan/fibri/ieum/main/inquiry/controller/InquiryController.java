package shinhan.fibri.ieum.main.inquiry.controller;

import jakarta.validation.Valid;
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
import shinhan.fibri.ieum.main.inquiry.service.InquiryService;

@RestController
@RequestMapping("/api/v1/inquiries")
public class InquiryController {

	private final InquiryService inquiryService;

	public InquiryController(InquiryService inquiryService) {
		this.inquiryService = inquiryService;
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
}
