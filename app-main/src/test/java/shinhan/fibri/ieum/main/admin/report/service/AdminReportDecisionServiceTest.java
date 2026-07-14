package shinhan.fibri.ieum.main.admin.report.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportDecisionTargetRow;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportLockedRow;

@ExtendWith(MockitoExtension.class)
class AdminReportDecisionServiceTest {

	@Mock
	private AdminReportRepository repository;

	private AdminReportDecisionService service;

	@BeforeEach
	void setUp() {
		service = new AdminReportDecisionService(repository);
	}

	@Test
	void locksObservedUserBeforeReportAndRevalidatesLockedRow() {
		when(repository.findDecisionTarget(10L)).thenReturn(Optional.of(new AdminReportDecisionTargetRow(2L)));
		when(repository.lockUserForDecision(2L)).thenReturn(true);
		when(repository.lockReportForDecision(10L))
			.thenReturn(Optional.of(new AdminReportLockedRow(2L, "pending", null, null)));
		when(repository.resolveReport(eq(10L), eq("confirmed"), eq(9L), any())).thenReturn(1);

		service.confirm(10L, 9L);

		InOrder order = inOrder(repository);
		order.verify(repository).findDecisionTarget(10L);
		order.verify(repository).lockUserForDecision(2L);
		order.verify(repository).lockReportForDecision(10L);
		order.verify(repository).resolveReport(eq(10L), eq("confirmed"), eq(9L), any());
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
	}
}
