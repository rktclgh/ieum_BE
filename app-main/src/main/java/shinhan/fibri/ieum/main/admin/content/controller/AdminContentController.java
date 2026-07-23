package shinhan.fibri.ieum.main.admin.content.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentDetailResponse;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListRequest;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListResponse;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentUpdateRequest;
import shinhan.fibri.ieum.main.admin.content.dto.HardDeleteContentRequest;
import shinhan.fibri.ieum.main.admin.content.service.AdminContentService;

@RestController
@RequestMapping("/api/v1/admin/content")
@RequiredArgsConstructor
public class AdminContentController {

	private final AdminContentService adminContentService;

	@GetMapping("/questions")
	public ResponseEntity<AdminContentListResponse> getQuestions(@Valid @ModelAttribute AdminContentListRequest request) {
		return ResponseEntity.ok(adminContentService.getQuestions(request));
	}

	@GetMapping("/meetings")
	public ResponseEntity<AdminContentListResponse> getMeetings(@Valid @ModelAttribute AdminContentListRequest request) {
		return ResponseEntity.ok(adminContentService.getMeetings(request));
	}

	@GetMapping("/{type}/{id}")
	public ResponseEntity<AdminContentDetailResponse> detail(@PathVariable String type, @PathVariable Long id) {
		return ResponseEntity.ok(adminContentService.detail(type, id));
	}

	@PatchMapping("/{type}/{id}")
	public ResponseEntity<AdminContentDetailResponse> update(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable String type,
		@PathVariable Long id,
		@Valid @RequestBody AdminContentUpdateRequest request
	) {
		return ResponseEntity.ok(adminContentService.update(principal, type, id, request));
	}

	@DeleteMapping("/{type}/{id}")
	public ResponseEntity<Void> hide(@PathVariable String type, @PathVariable Long id) {
		adminContentService.hide(type, id);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{type}/{id}/hard")
	public ResponseEntity<Void> hardDelete(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable String type,
		@PathVariable Long id,
		@Valid @RequestBody HardDeleteContentRequest request
	) {
		adminContentService.hardDelete(principal, type, id, request.confirmationToken());
		return ResponseEntity.noContent().build();
	}
}
