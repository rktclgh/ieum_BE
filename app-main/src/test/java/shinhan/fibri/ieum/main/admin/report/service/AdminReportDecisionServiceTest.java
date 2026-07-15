package shinhan.fibri.ieum.main.admin.report.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportConcurrentChangeException;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportAlreadyResolvedException;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportDecisionTargetRow;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportLockedRow;

@ExtendWith(MockitoExtension.class)
class AdminReportDecisionServiceTest {

	@Mock
	private AdminReportRepository repository;
	@Mock
	private AdminAuditLogWriter auditLogWriter;

	private AdminReportDecisionService service;

	@BeforeEach
	void setUp() {
		service = new AdminReportDecisionService(repository, auditLogWriter);
	}

	@Test
	void locksObservedUserBeforeReportAndRevalidatesLockedRow() {
		when(repository.findDecisionTarget(10L)).thenReturn(Optional.of(new AdminReportDecisionTargetRow(2L)));
		when(repository.lockUserForDecision(2L)).thenReturn(true);
		when(repository.lockReportForDecision(10L))
			.thenReturn(Optional.of(new AdminReportLockedRow(2L, "pending", null, null)));
		when(repository.resolveReport(eq(10L), eq("confirmed"), eq(9L), any())).thenReturn(1);

		service.confirm(10L, 9L);

		InOrder order = inOrder(repository, auditLogWriter);
		order.verify(repository).findDecisionTarget(10L);
		order.verify(repository).lockUserForDecision(2L);
		order.verify(repository).lockReportForDecision(10L);
		order.verify(repository).resolveReport(eq(10L), eq("confirmed"), eq(9L), any());
		order.verify(auditLogWriter).append(
			9L,
			AdminAuditAction.REPORT_CONFIRMED,
			"report",
			10L,
			java.util.Map.of("previousDecision", "pending", "newDecision", "confirmed")
		);
		verify(repository, never()).releaseLinkedAiSanctions(any(), any(), any(), any());
	}

	@Test
	void changedReportedUserAbortsBeforeAnyDecisionSideEffect() {
		when(repository.findDecisionTarget(10L)).thenReturn(Optional.of(new AdminReportDecisionTargetRow(2L)));
		when(repository.lockUserForDecision(2L)).thenReturn(true);
		when(repository.lockReportForDecision(10L))
			.thenReturn(Optional.of(new AdminReportLockedRow(3L, "pending", null, null)));

		assertThatThrownBy(() -> service.dismiss(10L, 9L))
			.isInstanceOf(AdminReportConcurrentChangeException.class);

		verify(repository, never()).resolveReport(any(), any(), any(), any());
		verify(repository, never()).releaseLinkedAiSanctions(any(), any(), any(), any());
		verify(auditLogWriter, never()).append(any(Long.class), any(), any(), any(Long.class), any());
	}

	@Test
	void dismissAuditsOnlyAfterDecisionSideEffectsSucceed() {
		when(repository.findDecisionTarget(10L)).thenReturn(Optional.of(new AdminReportDecisionTargetRow(2L)));
		when(repository.lockUserForDecision(2L)).thenReturn(true);
		when(repository.lockReportForDecision(10L))
			.thenReturn(Optional.of(new AdminReportLockedRow(2L, "ai_reviewed", null, null)));
		when(repository.resolveReport(eq(10L), eq("dismissed"), eq(9L), any())).thenReturn(1);

		service.dismiss(10L, 9L);

		InOrder order = inOrder(repository, auditLogWriter);
		order.verify(repository).resolveReport(eq(10L), eq("dismissed"), eq(9L), any());
		order.verify(repository).releaseLinkedAiSanctions(eq(10L), eq(2L), eq(9L), any());
		order.verify(repository).hasActiveSanctions(2L);
		order.verify(repository).activateUser(2L);
		order.verify(auditLogWriter).append(
			9L,
			AdminAuditAction.REPORT_DISMISSED,
			"report",
			10L,
			java.util.Map.of("previousDecision", "ai_reviewed", "newDecision", "dismissed")
		);
	}

	@Test
	void sameDecisionCancelsLiveAiWorkWithoutAppendingAnotherAuditEntry() {
		when(repository.findDecisionTarget(10L)).thenReturn(Optional.of(new AdminReportDecisionTargetRow(2L)));
		when(repository.lockUserForDecision(2L)).thenReturn(true);
		when(repository.lockReportForDecision(10L))
			.thenReturn(Optional.of(new AdminReportLockedRow(2L, "confirmed", null, null)));

		service.confirm(10L, 9L);

		verify(repository).cancelActiveAiWork(10L);
		verify(auditLogWriter, never()).append(any(Long.class), any(), any(), any(Long.class), any());
	}

	@Test
	void oppositeFinalDecisionDoesNotAppendAudit() {
		when(repository.findDecisionTarget(10L)).thenReturn(Optional.of(new AdminReportDecisionTargetRow(2L)));
		when(repository.lockUserForDecision(2L)).thenReturn(true);
		when(repository.lockReportForDecision(10L))
			.thenReturn(Optional.of(new AdminReportLockedRow(2L, "confirmed", null, null)));

		assertThatThrownBy(() -> service.dismiss(10L, 9L))
			.isInstanceOf(AdminReportAlreadyResolvedException.class);

		verify(auditLogWriter, never()).append(any(Long.class), any(), any(), any(Long.class), any());
	}

	@Test
	void failedCompareAndSetDoesNotAppendAudit() {
		when(repository.findDecisionTarget(10L)).thenReturn(Optional.of(new AdminReportDecisionTargetRow(2L)));
		when(repository.lockUserForDecision(2L)).thenReturn(true);
		when(repository.lockReportForDecision(10L))
			.thenReturn(Optional.of(new AdminReportLockedRow(2L, "pending", null, null)));
		when(repository.resolveReport(eq(10L), eq("confirmed"), eq(9L), any())).thenReturn(0);

		assertThatThrownBy(() -> service.confirm(10L, 9L))
			.isInstanceOf(AdminReportConcurrentChangeException.class);

		verify(auditLogWriter, never()).append(any(Long.class), any(), any(), any(Long.class), any());
	}

	@Test
	void failedDismissSideEffectDoesNotAppendAudit() {
		when(repository.findDecisionTarget(10L)).thenReturn(Optional.of(new AdminReportDecisionTargetRow(2L)));
		when(repository.lockUserForDecision(2L)).thenReturn(true);
		when(repository.lockReportForDecision(10L))
			.thenReturn(Optional.of(new AdminReportLockedRow(2L, "pending", null, null)));
		when(repository.resolveReport(eq(10L), eq("dismissed"), eq(9L), any())).thenReturn(1);
		doThrow(new IllegalStateException("sanction release failed"))
			.when(repository).releaseLinkedAiSanctions(eq(10L), eq(2L), eq(9L), any());

		assertThatThrownBy(() -> service.dismiss(10L, 9L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("sanction release failed");

		verify(auditLogWriter, never()).append(any(Long.class), any(), any(), any(Long.class), any());
	}
}
