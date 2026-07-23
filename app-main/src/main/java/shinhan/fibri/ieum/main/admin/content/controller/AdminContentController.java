package shinhan.fibri.ieum.main.admin.content.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentPreviewResponse;
import shinhan.fibri.ieum.main.admin.content.dto.HardDeleteContentRequest;
import shinhan.fibri.ieum.main.admin.content.service.AdminContentService;

@RestController
@RequestMapping("/api/v1/admin/content")
@RequiredArgsConstructor
public class AdminContentController {

	private final AdminContentService adminContentService;

	@GetMapping("/{type}/{id}")
	public ResponseEntity<AdminContentPreviewResponse> preview(@PathVariable String type, @PathVariable Long id) {
		return ResponseEntity.ok(adminContentService.preview(type, id));
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
