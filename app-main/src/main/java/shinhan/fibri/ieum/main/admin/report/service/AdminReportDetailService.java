package shinhan.fibri.ieum.main.admin.report.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportAiDetail;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportDecision;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportDetailResponse;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportResolution;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportSanctionItem;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportTargetSummary;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportUserSummary;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportNotFoundException;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportDetailRow;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportSanctionRow;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.report.domain.ReportAiReviewState;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;
import shinhan.fibri.ieum.main.report.domain.ReportTargetType;

@Service
@RequiredArgsConstructor
public class AdminReportDetailService {

	private final AdminReportRepository repository;
	private final AdminReportJsonSanitizer jsonSanitizer;

	@Transactional(readOnly = true)
	public AdminReportDetailResponse getReport(Long reportId) {
		AdminReportDetailRow row = repository.findDetail(reportId)
			.orElseThrow(AdminReportNotFoundException::new);
		ReportTargetType targetType = ReportTargetType.valueOf(row.targetType());
		ReportStatus status = ReportStatus.valueOf(row.status());
		List<AdminReportSanctionItem> sanctions = repository.findSanctions(reportId)
			.stream()
			.map(this::toSanction)
			.toList();
		return new AdminReportDetailResponse(
			row.reportId(),
			new AdminReportTargetSummary(targetType, row.targetId(), row.targetDeleted()),
			new AdminReportUserSummary(row.reporterId(), row.reporterNickname()),
			user(row.reportedUserId(), row.reportedUserNickname()),
			ReportReason.valueOf(row.reason()),
			row.detail(),
			status,
			jsonSanitizer.sanitizeContextSnapshot(targetType, row.contextSnapshot()),
			row.contextHash(),
			new AdminReportAiDetail(
				ReportAiReviewState.valueOf(row.aiReviewState()),
				row.aiRecommendation(),
				row.aiReason(),
				row.aiConfidence(),
				row.aiModelVersion(),
				row.aiPolicyVersion(),
				row.aiReviewedAt(),
				row.aiDecision() == null ? null : AdminReportDecision.valueOf(row.aiDecision()),
				row.aiPolicySetHash(),
				jsonSanitizer.sanitizeAiResult(row.aiReviewResult()),
				row.aiLastErrorCode()
			),
			resolution(status, row),
			sanctions,
			row.createdAt()
		);
	}

	private AdminReportResolution resolution(ReportStatus status, AdminReportDetailRow row) {
		if (row.resolvedById() == null) {
			return null;
		}
		return new AdminReportResolution(
			status,
			new AdminReportUserSummary(row.resolvedById(), row.resolvedByNickname()),
			row.resolvedAt()
		);
	}

	private AdminReportSanctionItem toSanction(AdminReportSanctionRow row) {
		return new AdminReportSanctionItem(
			row.sanctionId(),
			row.decisionSource(),
			SanctionType.valueOf(row.sanctionType()),
			row.reason(),
			user(row.adminId(), row.adminNickname()),
			row.startsAt(),
			row.endsAt(),
			row.releasedAt(),
			user(row.releasedById(), row.releasedByNickname()),
			row.createdAt()
		);
	}

	private AdminReportUserSummary user(Long userId, String nickname) {
		return userId == null ? null : new AdminReportUserSummary(userId, nickname);
	}
}
