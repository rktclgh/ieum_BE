package shinhan.fibri.ieum.main.admin.report.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportDetailResponse;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListRequest;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListResponse;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportDecisionService;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportDetailService;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportQueryService;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

	private final AdminReportQueryService queryService;
	private final AdminReportDetailService detailService;
	private final AdminReportDecisionService decisionService;

	@GetMapping
	public ResponseEntity<AdminReportListResponse> getReports(
		@Valid @ModelAttribute AdminReportListRequest request
	) {
		return ResponseEntity.ok(queryService.getReports(request));
	}

	@GetMapping("/{reportId}")
	public ResponseEntity<AdminReportDetailResponse> getReport(@PathVariable Long reportId) {
		return ResponseEntity.ok(detailService.getReport(reportId));
	}

	@PostMapping("/{reportId}/confirm")
	public ResponseEntity<Void> confirm(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long reportId
	) {
		decisionService.confirm(reportId, principal.userId());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{reportId}/dismiss")
	public ResponseEntity<Void> dismiss(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long reportId
	) {
		decisionService.dismiss(reportId, principal.userId());
		return ResponseEntity.noContent().build();
	}
}
