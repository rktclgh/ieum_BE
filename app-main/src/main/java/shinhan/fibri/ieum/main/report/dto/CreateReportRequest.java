package shinhan.fibri.ieum.main.report.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import shinhan.fibri.ieum.main.report.domain.ReportReason;

public record CreateReportRequest(
	@NotNull Long messageId,
	@NotNull ReportReason reason,
	@Size(max = 1000) String detail
) {
}
