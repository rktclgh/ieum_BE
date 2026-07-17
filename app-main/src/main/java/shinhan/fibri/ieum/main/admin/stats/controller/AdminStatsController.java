package shinhan.fibri.ieum.main.admin.stats.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.admin.stats.dto.AdminStatsOverviewResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.ContentStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.ReportStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.StatsOverviewRequest;
import shinhan.fibri.ieum.main.admin.stats.dto.StatsRangeRequest;
import shinhan.fibri.ieum.main.admin.stats.dto.UserStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.service.AdminStatsQueryService;

@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

	private final AdminStatsQueryService adminStatsQueryService;

	@GetMapping("/users")
	public ResponseEntity<UserStatsResponse> getUserStats(@Valid @ModelAttribute StatsRangeRequest request) {
		return ResponseEntity.ok(adminStatsQueryService.getUserStats(request));
	}

	@GetMapping("/content")
	public ResponseEntity<ContentStatsResponse> getContentStats(@Valid @ModelAttribute StatsRangeRequest request) {
		return ResponseEntity.ok(adminStatsQueryService.getContentStats(request));
	}

	@GetMapping("/reports")
	public ResponseEntity<ReportStatsResponse> getReportStats(@Valid @ModelAttribute StatsRangeRequest request) {
		return ResponseEntity.ok(adminStatsQueryService.getReportStats(request));
	}

	@GetMapping("/overview")
	public ResponseEntity<AdminStatsOverviewResponse> getOverview(@Valid @ModelAttribute StatsOverviewRequest request) {
		return ResponseEntity.ok(adminStatsQueryService.getOverview(request));
	}
}
