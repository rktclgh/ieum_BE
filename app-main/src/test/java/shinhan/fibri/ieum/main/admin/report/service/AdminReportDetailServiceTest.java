package shinhan.fibri.ieum.main.admin.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportNotFoundException;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportDetailRow;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportSanctionRow;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AdminReportDetailServiceTest {

	@Mock
	private AdminReportRepository repository;

	private AdminReportDetailService service;

	@BeforeEach
	void setUp() {
		service = new AdminReportDetailService(repository, new AdminReportJsonSanitizer(new ObjectMapper()));
	}

	@Test
	void missingReportThrowsDedicatedNotFoundError() {
		when(repository.findDetail(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getReport(999L))
			.isInstanceOf(AdminReportNotFoundException.class);
	}

	@Test
	void mapsSafeSnapshotAiResultResolverAndSanctionHistory() {
		when(repository.findDetail(10L)).thenReturn(Optional.of(detailRow()));
		when(repository.findSanctions(10L)).thenReturn(List.of(sanctionRow()));

		var detail = service.getReport(10L);

		assertThat(detail.target().id()).isEqualTo(1010L);
		assertThat(detail.target().deleted()).isTrue();
		assertThat(detail.contextSnapshot().at("/reported/messageId").asLong()).isEqualTo(1010L);
		assertThat(detail.contextSnapshot().toString()).doesNotContain("privateSnapshotField");
		assertThat(detail.ai().result().propertyNames()).containsExactlyInAnyOrder("category", "severity");
		assertThat(detail.ai().result().toString()).doesNotContain("providerAttempts", "chainOfThought", "SECRET");
		assertThat(detail.ai().lastErrorCode()).isEqualTo("SAFE_CODE");
		assertThat(detail.resolution().resolvedBy().nickname()).isEqualTo("resolver");
		assertThat(detail.sanctions()).hasSize(1);
		assertThat(detail.sanctions().getFirst().admin().nickname()).isEqualTo("resolver");
		assertThat(detail.toString()).doesNotContain(
			"SECRET_ERROR_MESSAGE", "aiLeaseUntil", "aiReviewAttemptId", "aiLockedBy"
		);
	}

	@Test
	void aiAnswerHasNoReportedUser() {
		AdminReportDetailRow row = aiAnswerRow();
		when(repository.findDetail(12L)).thenReturn(Optional.of(row));
		when(repository.findSanctions(12L)).thenReturn(List.of());

		var detail = service.getReport(12L);

		assertThat(detail.target().type().name()).isEqualTo("answer");
		assertThat(detail.reportedUser()).isNull();
	}

	private AdminReportDetailRow detailRow() {
		return new AdminReportDetailRow(
			10L, "message", 1010L, true, 1L, "reporter", 2L, "reported", "abuse", "detail",
			"confirmed", "{\"schemaVersion\":1,\"privateSnapshotField\":\"remove\",\"reported\":{\"messageId\":1010,\"content\":\"snapshot\"}}",
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "completed",
			"temporary_suspend", "safe reason", new BigDecimal("0.9000"), "model-v1", "policy-v1",
			OffsetDateTime.parse("2026-07-14T09:00:00Z"), "suspend",
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			"{\"category\":\"abuse\",\"severity\":\"high\",\"providerAttempts\":[{\"raw\":\"SECRET\"}],\"chainOfThought\":\"SECRET\"}",
			"SAFE_CODE", 9L, "resolver", OffsetDateTime.parse("2026-07-14T10:00:00Z"),
			OffsetDateTime.parse("2026-07-14T08:00:00Z")
		);
	}

	private AdminReportSanctionRow sanctionRow() {
		return new AdminReportSanctionRow(
			100L, "admin", "permanent", "reason", 9L, "resolver",
			OffsetDateTime.parse("2026-07-14T10:00:00Z"), null, null, null, null,
			OffsetDateTime.parse("2026-07-14T10:00:00Z")
		);
	}

	private AdminReportDetailRow aiAnswerRow() {
		return new AdminReportDetailRow(
			12L, "answer", 1212L, false, 1L, "reporter", null, null, "etc", "detail",
			"pending", "{\"schemaVersion\":1,\"targetType\":\"answer\",\"reported\":{\"answerId\":1212,\"authorId\":null,\"isAi\":true}}",
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cancelled",
			null, null, null, null, null, null, null, null, null, null, null, null, null,
			OffsetDateTime.parse("2026-07-14T08:00:00Z")
		);
	}
}
