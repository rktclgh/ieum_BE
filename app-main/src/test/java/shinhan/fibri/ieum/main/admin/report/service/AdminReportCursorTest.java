package shinhan.fibri.ieum.main.admin.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.admin.report.exception.InvalidAdminReportCursorException;

class AdminReportCursorTest {

	@Test
	void roundTripsInstantNanoAndReportIdWithUrlSafeOpaqueValue() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-14T10:11:12.987654321+09:00");

		String encoded = AdminReportCursor.encode(createdAt, 42L);
		AdminReportCursor.Position decoded = AdminReportCursor.decode(encoded);

		assertThat(encoded).doesNotContain("=", "+", "/");
		assertThat(decoded.createdAt().toInstant()).isEqualTo(createdAt.toInstant());
		assertThat(decoded.createdAt().getNano()).isEqualTo(987_654_321);
		assertThat(decoded.reportId()).isEqualTo(42L);
	}

	@Test
	void blankCursorMeansFirstPage() {
		assertThat(AdminReportCursor.decode(null)).isNull();
		assertThat(AdminReportCursor.decode("  ")).isNull();
	}

	@Test
	void rejectsCorruptedBase64() {
		assertThatThrownBy(() -> AdminReportCursor.decode("%%%"))
			.isInstanceOf(InvalidAdminReportCursorException.class);
	}

	@Test
	void rejectsUnsupportedVersion() {
		assertInvalidPayload("v2:1:0:1");
	}

	@Test
	void rejectsOutOfRangeNano() {
		assertInvalidPayload("v1:1:1000000000:1");
		assertInvalidPayload("v1:1:-1:1");
	}

	@Test
	void rejectsEpochOutsideTheSupportedInstantRange() {
		assertInvalidPayload("v1:9223372036854775807:0:1");
	}

	@Test
	void rejectsNonPositiveReportIdAndTrailingFields() {
		assertInvalidPayload("v1:1:0:0");
		assertInvalidPayload("v1:1:0:1:extra");
	}

	private void assertInvalidPayload(String payload) {
		String encoded = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(payload.getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> AdminReportCursor.decode(encoded))
			.isInstanceOf(InvalidAdminReportCursorException.class);
	}
}
