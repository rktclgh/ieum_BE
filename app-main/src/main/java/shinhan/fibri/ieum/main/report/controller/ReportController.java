package shinhan.fibri.ieum.main.report.controller;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.report.dto.CreateReportRequest;
import shinhan.fibri.ieum.main.report.dto.CreateReportResponse;
import shinhan.fibri.ieum.main.report.service.ReportService;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

	private final ReportService reportService;

	public ReportController(ReportService reportService) {
		this.reportService = reportService;
	}

	@PostMapping
	public ResponseEntity<CreateReportResponse> create(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody CreateReportRequest request
	) {
		CreateReportResponse response = reportService.create(principal, request);
		return ResponseEntity.created(URI.create("/api/v1/reports/" + response.reportId()))
			.body(response);
	}
}
