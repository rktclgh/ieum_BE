package shinhan.fibri.ieum.main.admin.knowledge.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeGraphRequest;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeGraphResponse;
import shinhan.fibri.ieum.main.admin.knowledge.service.KnowledgeGraphQueryService;

@RestController
@RequestMapping("/api/v1/admin/ai/knowledge/graph")
@RequiredArgsConstructor
public class AdminKnowledgeGraphController {

	private final KnowledgeGraphQueryService service;

	@GetMapping
	public ResponseEntity<AdminKnowledgeGraphResponse> graph(
		@Valid @ModelAttribute AdminKnowledgeGraphRequest request
	) {
		return ResponseEntity.ok(service.graph(request));
	}
}
