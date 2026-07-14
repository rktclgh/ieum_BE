package shinhan.fibri.ieum.main.report.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.report.dto.CreateAnswerReportRequest;
import shinhan.fibri.ieum.main.report.dto.CreateReportResponse;
import shinhan.fibri.ieum.main.report.service.ReportService;

@RestController
@RequestMapping("/api/v1/answers")
public class AnswerReportController {

	private final ReportService reportService;

	public AnswerReportController(ReportService reportService) {
		this.reportService = reportService;
	}

	@PostMapping("/{answerId}/report")
	public ResponseEntity<CreateReportResponse> create(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long answerId,
		@Valid @RequestBody CreateAnswerReportRequest request
	) {
		CreateReportResponse response = reportService.createAnswer(
			principal,
			answerId,
			request.reason(),
			request.detail()
		);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
