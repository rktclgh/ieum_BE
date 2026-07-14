package shinhan.fibri.ieum.main.admin.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportDecision;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListRequest;
import shinhan.fibri.ieum.main.admin.report.exception.InvalidAdminReportSizeException;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportListRow;
import shinhan.fibri.ieum.main.report.domain.ReportAiReviewState;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;

@ExtendWith(MockitoExtension.class)
class AdminReportQueryServiceTest {

	@Mock
	private AdminReportRepository repository;

	private AdminReportQueryService service;

	@BeforeEach
	void setUp() {
		service = new AdminReportQueryService(repository);
	}

	@Test
	void defaultsToTwentyAndUsesOneExtraRowForNextCursor() {
		List<AdminReportListRow> rows = LongStream.rangeClosed(1, 21)
			.mapToObj(id -> row(22 - id))
			.toList();
		when(repository.findReports(null, null, null, null, 21)).thenReturn(rows);

		var page = service.getReports(new AdminReportListRequest(null, null, null, null, null));

		assertThat(page.items()).hasSize(20);
		assertThat(AdminReportCursor.decode(page.nextCursor()).reportId()).isEqualTo(2L);
	}

	@Test
	void acceptsMaximumPageSizeAndPassesTypedFilterValues() {
		when(repository.findReports(eq("confirmed"), eq("completed"), eq("suspend"), any(), eq(51)))
			.thenReturn(List.of());
		String cursor = AdminReportCursor.encode(OffsetDateTime.parse("2026-07-14T10:00:00.000000001Z"), 100L);

		service.getReports(new AdminReportListRequest(
			ReportStatus.confirmed,
			ReportAiReviewState.completed,
			AdminReportDecision.suspend,
			cursor,
			50
		));

		verify(repository).findReports(eq("confirmed"), eq("completed"), eq("suspend"), any(), eq(51));
	}

	@Test
	void rejectsPageSizesOutsideOneToFiftyEvenWhenCalledOutsideMvc() {
		assertThatThrownBy(() -> service.getReports(new AdminReportListRequest(null, null, null, null, 0)))
			.isInstanceOf(InvalidAdminReportSizeException.class);
		assertThatThrownBy(() -> service.getReports(new AdminReportListRequest(null, null, null, null, 51)))
			.isInstanceOf(InvalidAdminReportSizeException.class);
	}

	@Test
	void mapsTargetReporterNullableReportedUserAndAiSummary() {
		when(repository.findReports(null, null, null, null, 21)).thenReturn(List.of(row(7L)));

		var item = service.getReports(new AdminReportListRequest(null, null, null, null, null)).items().getFirst();

		assertThat(item.reportId()).isEqualTo(7L);
		assertThat(item.target().type().name()).isEqualTo("message");
		assertThat(item.target().id()).isEqualTo(700L);
		assertThat(item.target().deleted()).isFalse();
		assertThat(item.reporter().nickname()).isEqualTo("reporter");
		assertThat(item.reportedUser()).isNull();
		assertThat(item.ai().reviewState()).isEqualTo(ReportAiReviewState.pending);
	}

	private AdminReportListRow row(long reportId) {
		return new AdminReportListRow(
			reportId,
			"message",
			reportId * 100,
			false,
			1L,
			"reporter",
			null,
			null,
			"spam",
			"pending",
			"pending",
			null,
			null,
			null,
			null,
			OffsetDateTime.parse("2026-07-14T10:00:00.000000001Z").minusSeconds(reportId)
		);
	}
}
