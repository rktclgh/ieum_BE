package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewImage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;

class ReportReviewPreparationServiceTest {

	@Test
	void validatesAndCollectsImagesBeforeBuildingModelEvidenceContext() {
		FakeFetcher fetcher = new FakeFetcher(webpBytes());
		ReportReviewPreparationService service = new ReportReviewPreparationService(
			new ReportReviewRequestValidator(),
			new ReportEvidenceImageBatchCollector(fetcher, 12L, 24L)
		);
		ReportReviewRequest request = new ReportReviewRequest(
			900L,
			UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
			2L,
			"harassment",
			"detail",
			"a".repeat(64),
			List.of(
				new ReportReviewMessage(1L, "other_actor_1", "context", null, "2026-07-11T00:00:00Z"),
				new ReportReviewMessage(
					2L,
					"reported_user",
					null,
					new ReportReviewImage("image/webp", "https://files.example.test/2"),
					"2026-07-11T00:01:00Z"
				)
			)
		);

		PreparedReportReview prepared = service.prepare(900L, request);

		assertThat(prepared.imageBatch().imagesByMessageId()).containsOnlyKeys(2L);
		assertThat(prepared.imageBatch().totalBytes()).isEqualTo(12L);
		assertThat(prepared.evidenceMessages())
			.extracting(ReportReviewEvidenceMessage::verifiedImage)
			.containsExactly(false, true);
		assertThat(fetcher.calls).isEqualTo(1);
	}

	private static byte[] webpBytes() {
		return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
	}

	private static final class FakeFetcher implements ReportEvidenceImageFetcher {

		private final Deque<byte[]> responses = new ArrayDeque<>();
		private int calls;

		private FakeFetcher(byte[]... responses) {
			for (byte[] response : responses) {
				this.responses.add(response);
			}
		}

		@Override
		public VerifiedReportEvidenceImage download(ReportReviewImage image, long maxAllowedBytes) {
			calls++;
			return new VerifiedReportEvidenceImage("image/webp", responses.removeFirst());
		}
	}
}
