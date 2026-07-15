package shinhan.fibri.ieum.main.admin.report.service;

import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportAlreadyResolvedException;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportConcurrentChangeException;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportNotFoundException;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportDecisionTargetRow;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportLockedRow;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;

@Service
@RequiredArgsConstructor
public class AdminReportDecisionService {

	private final AdminReportRepository repository;
	private final AdminAuditLogWriter auditLogWriter;

	@Transactional
	public void confirm(Long reportId, Long adminId) {
		decide(reportId, adminId, ReportStatus.confirmed);
	}

	@Transactional
	public void dismiss(Long reportId, Long adminId) {
		decide(reportId, adminId, ReportStatus.dismissed);
	}

	private void decide(Long reportId, Long adminId, ReportStatus decision) {
		AdminReportDecisionTargetRow observed = repository.findDecisionTarget(reportId)
			.orElseThrow(AdminReportNotFoundException::new);
		Long reportedUserId = observed.reportedUserId();
		if (reportedUserId != null && !repository.lockUserForDecision(reportedUserId)) {
			throw new AdminReportConcurrentChangeException();
		}

		AdminReportLockedRow locked = repository.lockReportForDecision(reportId)
			.orElseThrow(AdminReportNotFoundException::new);
		if (!Objects.equals(reportedUserId, locked.reportedUserId())) {
			throw new AdminReportConcurrentChangeException();
		}

		ReportStatus current = ReportStatus.valueOf(locked.status());
		OffsetDateTime now = OffsetDateTime.now();
		boolean changed = false;
		if (current == decision) {
			repository.cancelActiveAiWork(reportId);
		} else if (current == ReportStatus.confirmed || current == ReportStatus.dismissed) {
			throw new AdminReportAlreadyResolvedException();
		} else if (repository.resolveReport(reportId, decision.name(), adminId, now) != 1) {
			throw new AdminReportConcurrentChangeException();
		} else {
			changed = true;
		}

		if (decision == ReportStatus.dismissed && reportedUserId != null) {
			repository.releaseLinkedAiSanctions(reportId, reportedUserId, adminId, now);
			if (!repository.hasActiveSanctions(reportedUserId)) {
				repository.activateUser(reportedUserId);
			}
		}
		if (changed) {
			auditLogWriter.append(
				adminId,
				decision == ReportStatus.confirmed
					? AdminAuditAction.REPORT_CONFIRMED
					: AdminAuditAction.REPORT_DISMISSED,
				"report",
				reportId,
				java.util.Map.of("previousDecision", current.name(), "newDecision", decision.name())
			);
		}
	}
}
