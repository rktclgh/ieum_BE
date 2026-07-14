package shinhan.fibri.ieum.main.admin.stats.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
}
