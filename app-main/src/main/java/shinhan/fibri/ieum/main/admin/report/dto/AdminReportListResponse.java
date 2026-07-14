package shinhan.fibri.ieum.main.admin.report.dto;

import java.util.List;

public record AdminReportListResponse(
	List<AdminReportListItem> items,
	String nextCursor
) {
}
