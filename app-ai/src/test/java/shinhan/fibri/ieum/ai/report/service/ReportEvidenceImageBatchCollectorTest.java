package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewImage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;

class ReportEvidenceImageBatchCollectorTest {

	@Test
	void downloadsEachImageOnceAndIndexesTheVerifiedBytesByMessageId() {
		FakeFetcher fetcher = new FakeFetcher(webpBytes(), webpBytes());
		ReportEvidenceImageBatchCollector collector = new ReportEvidenceImageBatchCollector(fetcher, 12L, 24L);

		ReportEvidenceImageBatch batch = collector.collect(List.of(
			message(1L, true),
			message(2L, false),
			message(3L, true)
		));

		assertThat(batch.imagesByMessageId()).containsOnlyKeys(1L, 3L);
		assertThat(batch.totalBytes()).isEqualTo(24L);
		assertThat(fetcher.maxBytesByCall).containsExactly(12L, 12L);
	}

	@Test
	void rejectsBeforeDownloadingAnotherImageAfterTheTotalBudgetIsExhausted() {
		FakeFetcher fetcher = new FakeFetcher(webpBytes(), webpBytes());
		ReportEvidenceImageBatchCollector collector = new ReportEvidenceImageBatchCollector(fetcher, 12L, 24L);

		assertThatThrownBy(() -> collector.collect(List.of(
			message(1L, true),
			message(2L, true),
			message(3L, true)
		)))
			.isInstanceOf(ReportEvidenceImageDownloadException.class)
			.hasMessageContaining("total size");

		assertThat(fetcher.maxBytesByCall).containsExactly(12L, 12L);
	}

	@Test
	void keepsAnEmptyBatchForTextOnlyContextMessages() {
		FakeFetcher fetcher = new FakeFetcher();
		ReportEvidenceImageBatchCollector collector = new ReportEvidenceImageBatchCollector(fetcher, 12L, 24L);

		ReportEvidenceImageBatch batch = collector.collect(List.of(message(1L, false)));

		assertThat(batch.imagesByMessageId()).isEmpty();
		assertThat(batch.totalBytes()).isZero();
		assertThat(fetcher.maxBytesByCall).isEmpty();
	}

	@Test
	void rejectsDuplicateMessageIdsBeforeFetchingAnyImage() {
		FakeFetcher fetcher = new FakeFetcher(webpBytes());
		ReportEvidenceImageBatchCollector collector = new ReportEvidenceImageBatchCollector(fetcher, 12L, 24L);

		assertThatThrownBy(() -> collector.collect(List.of(
			message(1L, true),
			message(1L, true)
		)))
			.isInstanceOf(InvalidReportReviewRequestException.class)
			.hasMessageContaining("messageId");

		assertThat(fetcher.maxBytesByCall).isEmpty();
	}

	private ReportReviewMessage message(long messageId, boolean withImage) {
		return new ReportReviewMessage(
			messageId,
			"reported_user",
			withImage ? null : "text",
			withImage ? new ReportReviewImage("image/webp", "https://files.example.test/" + messageId) : null,
			"2026-07-11T00:00:00Z"
		);
	}

	private static byte[] webpBytes() {
		return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
	}

	private static final class FakeFetcher implements ReportEvidenceImageFetcher {

		private final Deque<byte[]> responses = new ArrayDeque<>();
		private final List<Long> maxBytesByCall = new ArrayList<>();

		private FakeFetcher(byte[]... responses) {
			for (byte[] response : responses) {
				this.responses.add(response);
			}
		}

		@Override
		public VerifiedReportEvidenceImage download(ReportReviewImage image, long maxBytes) {
			maxBytesByCall.add(maxBytes);
			return new VerifiedReportEvidenceImage("image/webp", responses.removeFirst());
		}
	}
}
