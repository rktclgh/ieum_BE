package shinhan.fibri.ieum.main.admin.knowledge.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateApproveRequest;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateDecisionResponse;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateDetailResponse;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateListRequest;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateListResponse;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateRejectRequest;
import shinhan.fibri.ieum.main.admin.knowledge.service.KnowledgeRelationCandidateDecisionService;
import shinhan.fibri.ieum.main.admin.knowledge.service.KnowledgeRelationCandidateDetailService;
import shinhan.fibri.ieum.main.admin.knowledge.service.KnowledgeRelationCandidateQueryService;

@RestController
@RequestMapping("/api/v1/admin/knowledge/relation-candidates")
@RequiredArgsConstructor
public class AdminKnowledgeCandidateController {

	private final KnowledgeRelationCandidateQueryService queryService;
	private final KnowledgeRelationCandidateDetailService detailService;
	private final KnowledgeRelationCandidateDecisionService decisionService;

	@GetMapping
	public ResponseEntity<AdminKnowledgeCandidateListResponse> list(
		@Valid @ModelAttribute AdminKnowledgeCandidateListRequest request
	) {
		return ResponseEntity.ok(queryService.list(request));
	}

	@GetMapping("/{candidateId}")
	public ResponseEntity<AdminKnowledgeCandidateDetailResponse> detail(@PathVariable Long candidateId) {
		return ResponseEntity.ok(detailService.get(candidateId));
	}

	@PostMapping("/{candidateId}/approve")
	public ResponseEntity<AdminKnowledgeCandidateDecisionResponse> approve(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long candidateId,
		@Valid @RequestBody AdminKnowledgeCandidateApproveRequest request
	) {
		return ResponseEntity.ok(decisionService.approve(candidateId, principal.userId(), request));
	}

	@PostMapping("/{candidateId}/reject")
	public ResponseEntity<AdminKnowledgeCandidateDecisionResponse> reject(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long candidateId,
		@Valid @RequestBody AdminKnowledgeCandidateRejectRequest request
	) {
		return ResponseEntity.ok(decisionService.reject(candidateId, principal.userId(), request));
	}
}
