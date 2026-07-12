package shinhan.fibri.ieum.ai.report.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;

public class ReportEvidenceImageBatchCollector {

	private static final long MINIMUM_WEBP_BYTES = 12L;

	private final ReportEvidenceImageFetcher imageFetcher;
	private final long maxImageBytes;
	private final long maxTotalBytes;

	public ReportEvidenceImageBatchCollector(ReportEvidenceImageFetcher imageFetcher, long maxImageBytes, long maxTotalBytes) {
		this.imageFetcher = Objects.requireNonNull(imageFetcher, "imageFetcher must not be null");
		if (maxImageBytes < MINIMUM_WEBP_BYTES || maxTotalBytes < maxImageBytes) {
			throw new IllegalArgumentException("image byte limits must be valid");
		}
		this.maxImageBytes = maxImageBytes;
		this.maxTotalBytes = maxTotalBytes;
	}

	public ReportEvidenceImageBatch collect(List<ReportReviewMessage> messages) {
		if (messages == null) {
			throw new IllegalArgumentException("messages must not be null");
		}
		validateUniqueMessageIds(messages);

		Map<Long, VerifiedReportEvidenceImage> imagesByMessageId = new LinkedHashMap<>();
		long totalBytes = 0;
		for (ReportReviewMessage message : messages) {
			if (message.image() == null) {
				continue;
			}

			long remainingBytes = maxTotalBytes - totalBytes;
			if (remainingBytes < MINIMUM_WEBP_BYTES) {
				throw new ReportEvidenceImageDownloadException("image evidence exceeds the total size limit");
			}
			long maxAllowedBytes = Math.min(maxImageBytes, remainingBytes);
			VerifiedReportEvidenceImage image = imageFetcher.download(message.image(), maxAllowedBytes);
			long imageBytes = image.byteSize();
			if (imageBytes > maxAllowedBytes || totalBytes > maxTotalBytes - imageBytes) {
				throw new ReportEvidenceImageDownloadException("image evidence exceeds the total size limit");
			}
			imagesByMessageId.put(message.messageId(), image);
			totalBytes += imageBytes;
		}
		return new ReportEvidenceImageBatch(imagesByMessageId, totalBytes);
	}

	private void validateUniqueMessageIds(List<ReportReviewMessage> messages) {
		Set<Long> messageIds = new HashSet<>();
		for (ReportReviewMessage message : messages) {
			if (message == null || message.messageId() < 1) {
				throw new InvalidReportReviewRequestException("messageId must be positive");
			}
			if (!messageIds.add(message.messageId())) {
				throw new InvalidReportReviewRequestException("messageId must be unique within the context");
			}
		}
	}
}
