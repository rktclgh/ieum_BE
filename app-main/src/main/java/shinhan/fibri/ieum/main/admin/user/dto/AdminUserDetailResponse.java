package shinhan.fibri.ieum.main.admin.user.dto;

import java.util.List;

public record AdminUserDetailResponse(
	AdminUserProfile user,
	AdminUserActivity activity,
	List<AdminReportItem> reports,
	List<AdminSanctionItem> sanctions
) {
}
