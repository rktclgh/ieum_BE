package shinhan.fibri.ieum.main.admin.content.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.admin.content.service.AdminContentService;

@RestController
@RequestMapping("/api/v1/admin/content")
@RequiredArgsConstructor
public class AdminContentController {

	private final AdminContentService adminContentService;

	@DeleteMapping("/{type}/{id}")
	public ResponseEntity<Void> hide(@PathVariable String type, @PathVariable Long id) {
		adminContentService.hide(type, id);
		return ResponseEntity.noContent().build();
	}
}
