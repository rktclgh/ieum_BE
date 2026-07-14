package shinhan.fibri.ieum.main.admin.report.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportAiSummary;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportDecision;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListItem;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListRequest;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListResponse;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportTargetSummary;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportUserSummary;
import shinhan.fibri.ieum.main.admin.report.exception.InvalidAdminReportSizeException;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportListRow;
import shinhan.fibri.ieum.main.report.domain.ReportAiReviewState;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;
import shinhan.fibri.ieum.main.report.domain.ReportTargetType;

@Service
@RequiredArgsConstructor
public class AdminReportQueryService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;

	private final AdminReportRepository repository;

	@Transactional(readOnly = true)
	public AdminReportListResponse getReports(AdminReportListRequest request) {
		int size = normalizeSize(request.size());
		AdminReportCursor.Position cursor = AdminReportCursor.decode(request.cursor());
		List<AdminReportListRow> rows = repository.findReports(
			name(request.status()),
			name(request.aiReviewState()),
			name(request.decision()),
			cursor,
			size + 1
		);
		boolean hasNext = rows.size() > size;
		List<AdminReportListItem> items = rows.stream()
			.limit(size)
			.map(this::toItem)
			.toList();
		String nextCursor = hasNext
			? AdminReportCursor.encode(items.getLast().createdAt(), items.getLast().reportId())
			: null;
		return new AdminReportListResponse(items, nextCursor);
	}

	private int normalizeSize(Integer requested) {
		if (requested == null) {
			return DEFAULT_SIZE;
		}
		if (requested < 1 || requested > MAX_SIZE) {
			throw new InvalidAdminReportSizeException();
		}
		return requested;
	}

	private AdminReportListItem toItem(AdminReportListRow row) {
		return new AdminReportListItem(
			row.reportId(),
			new AdminReportTargetSummary(
				ReportTargetType.valueOf(row.targetType()),
				row.targetId(),
				row.targetDeleted()
			),
			new AdminReportUserSummary(row.reporterId(), row.reporterNickname()),
			row.reportedUserId() == null
				? null
				: new AdminReportUserSummary(row.reportedUserId(), row.reportedUserNickname()),
			ReportReason.valueOf(row.reason()),
			ReportStatus.valueOf(row.status()),
			new AdminReportAiSummary(
				ReportAiReviewState.valueOf(row.aiReviewState()),
				row.aiRecommendation(),
				row.aiDecision() == null ? null : AdminReportDecision.valueOf(row.aiDecision()),
				row.aiConfidence(),
				row.aiReviewedAt()
			),
			row.createdAt()
		);
	}

	private String name(Enum<?> value) {
		return value == null ? null : value.name();
	}
}
