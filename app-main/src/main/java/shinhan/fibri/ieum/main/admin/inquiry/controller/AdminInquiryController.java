package shinhan.fibri.ieum.main.admin.inquiry.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryListRequest;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AnswerInquiryRequest;
import shinhan.fibri.ieum.main.admin.user.dto.CursorPage;
import shinhan.fibri.ieum.main.admin.inquiry.service.AdminInquiryAnswerService;
import shinhan.fibri.ieum.main.admin.inquiry.service.AdminInquiryQueryService;

@RestController
@RequestMapping("/api/v1/admin/inquiries")
public class AdminInquiryController {

	private final AdminInquiryQueryService queryService;
	private final AdminInquiryAnswerService answerService;

	public AdminInquiryController(
		AdminInquiryQueryService queryService,
		AdminInquiryAnswerService answerService
	) {
		this.queryService = queryService;
		this.answerService = answerService;
	}

	@GetMapping
	public ResponseEntity<CursorPage<AdminInquiryItem>> list(
		@Valid @ModelAttribute AdminInquiryListRequest request
	) {
		return ResponseEntity.ok(queryService.list(request));
	}

	@GetMapping("/{inquiryId}")
	public ResponseEntity<AdminInquiryItem> get(@PathVariable Long inquiryId) {
		return ResponseEntity.ok(queryService.get(inquiryId));
	}

	@PostMapping("/{inquiryId}/answer")
	public ResponseEntity<Void> answer(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long inquiryId,
		@Valid @RequestBody AnswerInquiryRequest request
	) {
		answerService.answer(principal, inquiryId, request);
		return ResponseEntity.noContent().build();
	}
}
